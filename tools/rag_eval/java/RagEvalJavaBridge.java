import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.FileSystemResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class RagEvalJavaBridge {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("Usage: RagEvalJavaBridge <pdf-path>");
        }

        Path pdfPath = Path.of(args[0]).toAbsolutePath().normalize();
        TikaDocumentReader reader = new TikaDocumentReader(new FileSystemResource(pdfPath));
        String text = reader.get().stream()
                .map(Document::getContent)
                .collect(Collectors.joining("\n"));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source_file", pdfPath.getFileName().toString());
        metadata.put("parser", "baseline_tika_token");

        String sourceId = UUID.nameUUIDFromBytes(pdfPath.toString().getBytes(StandardCharsets.UTF_8)).toString();
        Document source = new Document(sourceId, text, metadata);
        List<Document> chunks = new TokenTextSplitter().apply(List.of(source));

        List<Map<String, Object>> output = new ArrayList<>();
        int index = 0;
        for (Document chunk : chunks) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", chunk.getId());
            item.put("chunk_index", index++);
            item.put("content", chunk.getContent());
            Map<String, Object> chunkMetadata = new LinkedHashMap<>(chunk.getMetadata());
            chunkMetadata.put("source_file", pdfPath.getFileName().toString());
            chunkMetadata.put("parser", "baseline_tika_token");
            chunkMetadata.put("source_doc_id", sourceId);
            item.put("metadata", chunkMetadata);
            output.add(item);
        }

        ObjectMapper mapper = new ObjectMapper();
        System.out.println(mapper.writeValueAsString(output));
    }
}
