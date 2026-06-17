package com.codeguardian.mcp.tools;

import com.codeguardian.mcp.McpServerOptions;
import com.codeguardian.mcp.McpToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class SummarizeFileTool extends ReadOnlyToolSupport {

    private static final Pattern IMPORT_PATTERN = Pattern.compile("^\\s*(import|using|require\\(|from\\s+\\S+\\s+import|#include)\\b.*");
    private static final Pattern SYMBOL_PATTERN = Pattern.compile(
            "^\\s*((public|private|protected|static|final|abstract|async|export|def|class|interface|enum|record)\\b.*|[A-Za-z_$][\\w$<>\\[\\], ?]+\\s+[A-Za-z_$][\\w$]*\\s*\\([^;]*\\)\\s*(\\{|throws\\b.*)?)");

    private final RiskSignalScanner riskSignalScanner = new RiskSignalScanner();

    public SummarizeFileTool(McpServerOptions options) {
        super(options);
    }

    @Override
    public String name() {
        return "codeguardian.summarize_file";
    }

    @Override
    public String title() {
        return "Summarize File";
    }

    @Override
    public String description() {
        return "Read-only source file summary. Returns metadata, symbols, imports, and risk signals; the full source body is intentionally omitted.";
    }

    @Override
    public ObjectNode inputSchema(ObjectMapper mapper) {
        ObjectNode schema = schema(mapper);
        ObjectNode properties = (ObjectNode) schema.get("properties");
        properties.set("path", property(mapper, "string", "Relative path to the file under the MCP root."));
        properties.set("maxSymbols", property(mapper, "integer", "Maximum symbol/declaration lines to return."));
        schema.set("required", required(mapper, "path"));
        return schema;
    }

    @Override
    public McpToolResult call(JsonNode arguments) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String pathArg = stringArg(arguments, "path", "");
        if (pathArg.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        int maxSymbols = intArg(arguments, "maxSymbols", 30, 1, 100);
        Path file = paths.resolve(pathArg);
        validateFile(file);

        List<String> lines = SourceText.readLinesCapped(file, options.maxFileBytes());
        List<String> imports = extractImports(lines);
        List<Map<String, Object>> symbols = extractSymbols(lines, maxSymbols);
        long sizeBytes = Files.size(file);
        boolean truncated = sizeBytes > options.maxFileBytes();

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("path", paths.relative(file));
        content.put("language", SourceText.language(file));
        content.put("extension", SourceText.extension(file));
        content.put("sizeBytes", sizeBytes);
        content.put("loadedBytesLimit", options.maxFileBytes());
        content.put("truncatedForSummary", truncated);
        content.put("loadedLineCount", lines.size());
        content.put("sha256", SourceText.sha256File(file));
        content.put("imports", imports);
        content.put("symbols", symbols);
        content.put("riskSignals", riskSignalScanner.scan(lines, 30));
        content.put("summary", summarySentence(file, lines, imports, symbols, truncated));
        content.put("contentPolicy", "summary_only_full_source_omitted_by_default");
        return result(mapper, content);
    }

    private void validateFile(Path file) {
        if (!Files.isRegularFile(file) || !paths.isAllowed(file)) {
            throw new IllegalArgumentException("file is not readable under the MCP root");
        }
        if (!SourceText.isTextFile(file)) {
            throw new IllegalArgumentException("file does not look like a supported text/source file");
        }
    }

    private List<String> extractImports(List<String> lines) {
        List<String> imports = new ArrayList<>();
        for (String sourceLine : lines) {
            String line = sourceLine.trim();
            if (imports.size() >= 30) {
                return imports;
            }
            if (!line.isBlank() && IMPORT_PATTERN.matcher(line).matches()) {
                imports.add(SourceText.trim(line, 180));
            }
        }
        return imports;
    }

    private List<Map<String, Object>> extractSymbols(List<String> lines, int maxSymbols) {
        List<Map<String, Object>> symbols = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isBlank() || isControlFlow(line)) {
                continue;
            }
            if (SYMBOL_PATTERN.matcher(line).matches()) {
                symbols.add(Map.of("line", i + 1, "signature", SourceText.trim(stripBody(line), 220)));
            }
            if (symbols.size() >= maxSymbols) {
                return symbols;
            }
        }
        return symbols;
    }

    private boolean isControlFlow(String line) {
        String normalized = line.stripLeading();
        return normalized.startsWith("if ")
                || normalized.startsWith("if(")
                || normalized.startsWith("for ")
                || normalized.startsWith("for(")
                || normalized.startsWith("while ")
                || normalized.startsWith("while(")
                || normalized.startsWith("switch ")
                || normalized.startsWith("catch ");
    }

    private String stripBody(String line) {
        int bodyStart = line.indexOf('{');
        return bodyStart >= 0 ? line.substring(0, bodyStart + 1) : line;
    }

    private String summarySentence(Path file,
                                   List<String> lines,
                                   List<String> imports,
                                   List<Map<String, Object>> symbols,
                                   boolean truncated) {
        return "%s file with %d loaded lines, %d imports/includes, and %d visible declarations%s. Full source is omitted."
                .formatted(SourceText.language(file), lines.size(), imports.size(), symbols.size(), truncated ? " in the scanned prefix" : "");
    }
}
