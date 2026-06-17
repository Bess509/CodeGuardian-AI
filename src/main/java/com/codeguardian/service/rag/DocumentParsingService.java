package com.codeguardian.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentParsingService {

    private static final int MIN_STRUCTURED_CONTENT_LENGTH = 40;

    private final ObjectMapper objectMapper;

    @Value("${app.rag.parser.enabled:true}")
    private boolean structuredParserEnabled;

    @Value("${app.rag.parser.python-command:python}")
    private String pythonCommand;

    @Value("${app.rag.parser.script-path:scripts/rag_parse_document.py}")
    private String scriptPath;

    @Value("${app.rag.parser.timeout-seconds:180}")
    private long timeoutSeconds;

    public ParsedKnowledgeDocument parse(MultipartFile file) throws IOException {
        if (structuredParserEnabled) {
            ParsedKnowledgeDocument parsed = tryStructuredParser(file);
            if (parsed != null && hasEnoughContent(parsed.getContent())) {
                return parsed;
            }
        }
        return parseWithTika(file, "tika_fallback");
    }

    private ParsedKnowledgeDocument tryStructuredParser(MultipartFile file) {
        Path script = Path.of(scriptPath).toAbsolutePath().normalize();
        if (!Files.exists(script)) {
            log.warn("Structured RAG parser script not found: {}", script);
            return null;
        }

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("codeguardian-rag-", safeExtension(file.getOriginalFilename()));
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            ProcessBuilder builder = new ProcessBuilder(
                    pythonCommand,
                    script.toString(),
                    tempFile.toString()
            );
            Path workingDirectory = script.getParent() != null ? script.getParent().getParent() : null;
            if (workingDirectory != null) {
                builder.directory(workingDirectory.toFile());
            }
            Process process = builder.start();

            CompletableFuture<String> stdout = CompletableFuture.supplyAsync(() -> readStream(process.getInputStream()));
            CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> readStream(process.getErrorStream()));

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("Structured RAG parser timed out after {} seconds", timeoutSeconds);
                return null;
            }

            String output = stdout.get(5, TimeUnit.SECONDS);
            String error = stderr.get(5, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                log.warn("Structured RAG parser failed with exitCode={}, stderr={}", process.exitValue(), trim(error, 600));
                return null;
            }

            ParserResponse response = objectMapper.readValue(output, ParserResponse.class);
            if (!response.success || !hasEnoughContent(response.content)) {
                log.warn("Structured RAG parser returned no usable content: {}", response.errorMessage);
                return null;
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            if (response.metadata != null) {
                metadata.putAll(response.metadata);
            }
            metadata.put("parser", response.parser);
            metadata.put("parserRuntime", "python");
            metadata.put("parserTimeoutSeconds", timeoutSeconds);

            return ParsedKnowledgeDocument.builder()
                    .content(response.content)
                    .parser(response.parser)
                    .parserStrategy(stringValue(metadata.get("parserStrategy"), "structured_parser"))
                    .metadata(metadata)
                    .build();
        } catch (Exception e) {
            log.warn("Structured RAG parsing failed, falling back to Tika: {}", e.getMessage());
            return null;
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // Best effort cleanup.
                }
            }
        }
    }

    private ParsedKnowledgeDocument parseWithTika(MultipartFile file, String strategy) throws IOException {
        Resource resource = new InputStreamResource(file.getInputStream());
        TikaDocumentReader tikaReader = new TikaDocumentReader(resource);
        List<Document> tikaDocs = tikaReader.get();

        StringBuilder textBuilder = new StringBuilder();
        for (Document doc : tikaDocs) {
            if (doc != null && doc.getContent() != null) {
                textBuilder.append(doc.getContent()).append('\n');
            }
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("parser", "tika");
        metadata.put("parserStrategy", strategy);
        metadata.put("structured", false);

        return ParsedKnowledgeDocument.builder()
                .content(textBuilder.toString().trim())
                .parser("tika")
                .parserStrategy(strategy)
                .metadata(metadata)
                .build();
    }

    private boolean hasEnoughContent(String content) {
        return content != null && content.trim().length() >= MIN_STRUCTURED_CONTENT_LENGTH;
    }

    private String safeExtension(String filename) {
        if (filename == null || filename.isBlank()) {
            return ".bin";
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return ".bin";
        }
        String extension = filename.substring(dot).replaceAll("[^A-Za-z0-9.]", "");
        if (extension.length() < 2 || extension.length() > 16) {
            return ".bin";
        }
        return extension;
    }

    private String readStream(InputStream input) {
        try (InputStream stream = input) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private String stringValue(Object value, String fallback) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return String.valueOf(value);
    }

    private String trim(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    @SuppressWarnings("unused")
    private static class ParserResponse {
        public boolean success;
        public String parser;
        public String content;
        public Map<String, Object> metadata;
        public String errorCode;
        public String errorMessage;
    }
}
