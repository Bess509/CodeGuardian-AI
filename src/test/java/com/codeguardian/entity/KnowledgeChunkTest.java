package com.codeguardian.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeChunkTest {

    @Test
    void prePersistShouldInitializeTimestamps() {
        KnowledgeChunk chunk = KnowledgeChunk.builder()
                .id("chunk-1")
                .documentId("doc-1")
                .chunkIndex(0)
                .content("content")
                .build();

        chunk.prePersist();

        assertNotNull(chunk.getCreatedAt());
        assertNotNull(chunk.getUpdatedAt());
    }

    @Test
    void preUpdateShouldRefreshUpdatedAt() {
        LocalDateTime createdAt = LocalDateTime.now().minusDays(1);
        LocalDateTime oldUpdatedAt = LocalDateTime.now().minusHours(1);
        KnowledgeChunk chunk = KnowledgeChunk.builder()
                .id("chunk-1")
                .documentId("doc-1")
                .chunkIndex(0)
                .content("content")
                .createdAt(createdAt)
                .updatedAt(oldUpdatedAt)
                .build();

        chunk.preUpdate();

        assertTrue(chunk.getUpdatedAt().isAfter(oldUpdatedAt));
    }
}
