package com.codeguardian.service.rag;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Data
@Component
@ConfigurationProperties(prefix = "app.rag.chunking")
public class ChunkingProperties {

    private String strategyVersion = "structure_rule_sentence_token_v1";
    private int targetTokens = 550;
    private int hardMaxTokens = 800;
    private int overlapTokens = 100;
    private int minChunkTokens = 80;
    private int maxRuleIdsPerChunk = 2;
    private int maxHeadingLength = 80;

    public String configHash() {
        String canonical = String.join("|",
                "targetTokens=" + targetTokens,
                "hardMaxTokens=" + hardMaxTokens,
                "overlapTokens=" + overlapTokens,
                "minChunkTokens=" + minChunkTokens,
                "maxRuleIdsPerChunk=" + maxRuleIdsPerChunk,
                "maxHeadingLength=" + maxHeadingLength);
        return sha256(canonical);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
