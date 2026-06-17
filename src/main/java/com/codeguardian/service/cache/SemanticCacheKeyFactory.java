package com.codeguardian.service.cache;

import com.codeguardian.config.ReviewCacheProperties;
import com.codeguardian.service.rag.KnowledgeBaseService;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
final class SemanticCacheKeyFactory {

    private static final String KEY_PREFIX = "review:fp";

    private final ReviewCacheProperties properties;
    private final KnowledgeBaseService knowledgeBaseService;

    SemanticCacheKeyFactory(ReviewCacheProperties properties, KnowledgeBaseService knowledgeBaseService) {
        this.properties = properties;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    String scopePrefix(String language, String providerCode, boolean enableRag) {
        return KEY_PREFIX
                + ":" + safeToken(properties.getNamespaceVersion())
                + ":" + safeToken(properties.getPromptVersion())
                + ":" + safeToken(language)
                + ":" + safeToken(providerCode)
                + ":" + ragScopeToken(enableRag);
    }

    String exactKey(String scopePrefix, String exactHash) {
        return scopePrefix + ":exact:" + exactHash;
    }

    List<String> bucketKeys(String scopePrefix, long simHash64) {
        int segments = Math.max(1, properties.getSimhash().getSegments());
        return SemanticFingerprintCalculator.bucketKeys(scopePrefix, simHash64, segments);
    }

    String normalizeLanguage(String language) {
        if (language == null || language.isBlank()) {
            return "unknown";
        }
        return language.trim().toLowerCase();
    }

    private String ragScopeToken(boolean enableRag) {
        if (!enableRag) {
            return "rag0";
        }
        String fingerprint = null;
        try {
            if (knowledgeBaseService != null) {
                fingerprint = knowledgeBaseService.getCorpusFingerprint();
            }
        } catch (Exception e) {
            log.warn("Failed to calculate knowledge-base fingerprint for cache scope: {}", e.getMessage());
        }
        return "rag1:kb_" + safeToken(fingerprint != null && !fingerprint.isBlank() ? fingerprint : "unavailable");
    }

    private String safeToken(String token) {
        if (token == null || token.isBlank()) {
            return "na";
        }
        return token.trim().replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
