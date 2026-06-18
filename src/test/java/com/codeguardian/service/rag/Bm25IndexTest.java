package com.codeguardian.service.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Bm25IndexTest {

    @Test
    void search_ShouldUseChunkMetadataFields_WhenBuildingIndexText() {
        KnowledgeDocument chunk = KnowledgeDocument.builder()
                .id("chunk-auth-1")
                .title("Authentication checklist")
                .content("Use parameter binding for database queries.")
                .category("SECURITY")
                .metadata(Map.of(
                        "source_doc_id", "doc-auth",
                        "heading_path", "Security > SQL Injection",
                        "rule_ids", List.of("CG-SQL-001")
                ))
                .build();
        Bm25Index index = new Bm25Index();
        index.rebuild(List.of(chunk));

        assertEquals(List.of(0), index.search("CG-SQL-001", 5));
        assertEquals(List.of(0), index.search("SQL Injection", 5));
        assertEquals(List.of(0), index.search("SECURITY", 5));
    }

    @Test
    void rebuildChunks_ShouldPreserveChunkIdentityFromVectorDocumentMetadata() {
        Document chunk = new Document("vector-row-1", "Prepared statements prevent injection.",
                Map.of(
                        "chunk_id", "chunk-sql-7",
                        "source_doc_id", "doc-security",
                        "title", "SQL Security",
                        "heading_path", "Security > Database",
                        "rule_ids", List.of("CWE-89"),
                        "category", "SECURITY"
                ));
        Bm25Index index = new Bm25Index();
        index.rebuildChunks(List.of(chunk));

        Integer resultIndex = index.search("CWE-89", 1).get(0);
        Bm25Index.IndexedChunk indexedChunk = index.get(resultIndex);

        assertEquals("chunk-sql-7", indexedChunk.chunkId());
        assertEquals("doc-security", indexedChunk.sourceDocumentId());
        assertEquals("SQL Security", indexedChunk.title());
        assertEquals("Prepared statements prevent injection.", indexedChunk.content());
        assertEquals("Security > Database", indexedChunk.metadata().get("heading_path"));
    }
}
