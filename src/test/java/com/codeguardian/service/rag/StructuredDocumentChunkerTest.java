package com.codeguardian.service.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredDocumentChunkerTest {

    @Test
    void split_ShouldPreserveHeadingRuleMetadataAndUseSmallOverlap() {
        String content = """
                # Security Rules

                RULE-SQL-001 SQL injection
                Use prepared statements for SQL queries.

                ## Secrets

                SAFE-SECRET-001 Do not hardcode secrets.
                API tokens must come from secret storage.
                """;

        List<Document> chunks = StructuredDocumentChunker.split("doc-a", content, Map.of("title", "Rules"));

        assertFalse(chunks.isEmpty());
        Document first = chunks.get(0);
        assertEquals("doc-a", first.getMetadata().get("source_doc_id"));
        assertEquals("heading_rule_aware_overlap", first.getMetadata().get("chunk_strategy"));
        assertEquals(chunks.size(), first.getMetadata().get("chunk_count"));
        assertTrue(first.getMetadata().containsKey("heading_path"));
        assertTrue(first.getMetadata().containsKey("rule_ids"));
    }

    @Test
    void split_ShouldChunkLargeSections() {
        String repeated = "Use prepared statements and validate input.\n".repeat(300);

        List<Document> chunks = StructuredDocumentChunker.split("doc-b", "# Security\n" + repeated, Map.of());

        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(chunk ->
                Integer.valueOf(320).equals(chunk.getMetadata().get("chunk_overlap_chars"))));
    }
}
