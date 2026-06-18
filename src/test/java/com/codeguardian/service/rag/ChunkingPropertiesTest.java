package com.codeguardian.service.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ChunkingPropertiesTest {

    @Test
    void shouldExposeRagChunkingDefaults() {
        ChunkingProperties properties = new ChunkingProperties();

        assertEquals("structure_rule_sentence_token_v1", properties.getStrategyVersion());
        assertEquals(550, properties.getTargetTokens());
        assertEquals(800, properties.getHardMaxTokens());
        assertEquals(100, properties.getOverlapTokens());
        assertEquals(80, properties.getMinChunkTokens());
        assertEquals(2, properties.getMaxRuleIdsPerChunk());
        assertEquals(80, properties.getMaxHeadingLength());
    }

    @Test
    void configHashShouldChangeWhenChunkingParametersChange() {
        ChunkingProperties properties = new ChunkingProperties();
        String firstHash = properties.configHash();

        properties.setOverlapTokens(properties.getOverlapTokens() + 1);

        assertNotEquals(firstHash, properties.configHash());
    }
}
