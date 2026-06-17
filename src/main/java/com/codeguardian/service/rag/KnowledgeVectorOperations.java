package com.codeguardian.service.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
final class KnowledgeVectorOperations {

    private final JdbcTemplate jdbcTemplate;
    private final VectorStore vectorStore;

    KnowledgeVectorOperations(JdbcTemplate jdbcTemplate, VectorStore vectorStore) {
        this.jdbcTemplate = jdbcTemplate;
        this.vectorStore = vectorStore;
    }

    void checkAndFixVectorSchema() {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM information_schema.tables WHERE table_name = 'vector_store'",
                    Integer.class
            );
            if (count == null || count <= 0) {
                return;
            }

            String type = jdbcTemplate.queryForObject(
                    "SELECT format_type(atttypid, atttypmod) FROM pg_attribute " +
                            "WHERE attrelid = 'vector_store'::regclass AND attname = 'embedding'",
                    String.class
            );
            log.info("Current vector_store.embedding type: {}", type);
            if (type != null && !type.contains("(384)")) {
                log.warn("Detected incorrect vector dimensions (expected 384). Fixing schema...");
                jdbcTemplate.execute("TRUNCATE TABLE vector_store");
                jdbcTemplate.execute("ALTER TABLE vector_store ALTER COLUMN embedding TYPE vector(384)");
                log.info("Schema fixed successfully. Please re-upload documents if needed.");
            }
        } catch (Exception e) {
            log.error("Failed to check/fix vector schema: {}", e.getMessage());
        }
    }

    void vectorizeDocument(KnowledgeDocument doc) {
        if (vectorStore == null || doc == null) {
            return;
        }
        String content = doc.getTitle() + "\n" + doc.getContent();
        Map<String, Object> baseMetadata = doc.getMetadata() != null ? new HashMap<>(doc.getMetadata()) : new HashMap<>();
        baseMetadata.put("source_doc_id", doc.getId());
        baseMetadata.put("title", doc.getTitle());
        baseMetadata.put("category", doc.getCategory());

        List<Document> chunks = StructuredDocumentChunker.split(doc.getId(), content, baseMetadata);
        if (chunks.isEmpty()) {
            log.warn("Document splitting resulted in 0 chunks for doc: {}", doc.getTitle());
            return;
        }
        for (Document chunk : chunks) {
            chunk.getMetadata().put("source_doc_id", doc.getId());
            chunk.getMetadata().put("title", doc.getTitle());
            chunk.getMetadata().put("category", doc.getCategory());
        }
        log.info("Structured RAG chunking completed: docId={}, chunks={}", doc.getId(), chunks.size());
        try {
            vectorStore.add(chunks);
        } catch (Exception e) {
            log.error("Failed to add document to Vector Store: {}", e.getMessage(), e);
        }
    }
}
