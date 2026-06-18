package com.codeguardian.service.rag;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class RerankAuditSummary {

    private RerankAuditSummary() {
    }

    static Map<String, Object> fromChunks(List<RetrievedKnowledgeChunk> chunks) {
        List<RetrievedKnowledgeChunk> safeChunks = chunks != null ? chunks : List.of();
        List<Map<String, Object>> chunkSummaries = safeChunks.stream()
                .map(RerankAuditSummary::chunkSummary)
                .filter(summary -> !summary.isEmpty())
                .toList();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("applied", chunkSummaries.stream().anyMatch(item -> Boolean.TRUE.equals(item.get("reranked"))));
        summary.put("rerankedChunkCount", chunkSummaries.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("reranked")))
                .count());
        summary.put("missingScoreCount", chunkSummaries.stream()
                .filter(item -> Boolean.TRUE.equals(item.get("rerankMissingScore")))
                .count());
        firstValue(chunkSummaries, "reranker").ifPresent(value -> summary.put("reranker", value));
        firstValue(chunkSummaries, "rerankerModel").ifPresent(value -> summary.put("rerankerModel", value));
        firstValue(chunkSummaries, "rerankerEndpoint").ifPresent(value -> summary.put("rerankerEndpoint", value));
        chunkSummaries.stream()
                .map(item -> item.get("rerankCandidateCount"))
                .filter(Objects::nonNull)
                .findFirst()
                .ifPresent(value -> summary.put("candidateCount", value));
        if (!safeChunks.isEmpty()) {
            RetrievedKnowledgeChunk top = safeChunks.get(0);
            summary.put("topChunkId", top.getChunkId());
            summary.put("topSourceDocumentId", top.getSourceDocumentId());
            summary.put("topRank", top.getRank());
            putIfPresent(summary, "topCrossEncoderScore", top.getMetadata(), "crossEncoderScore");
            putIfPresent(summary, "topFinalRerankScore", top.getMetadata(), "finalRerankScore");
            putIfPresent(summary, "topFusionScoreBeforeRerank", top.getMetadata(), "fusionScoreBeforeRerank");
        }
        if (!chunkSummaries.isEmpty()) {
            summary.put("chunks", chunkSummaries);
        }
        return summary;
    }

    static Map<String, Object> fromChunk(RetrievedKnowledgeChunk chunk) {
        return fromChunks(chunk != null ? List.of(chunk) : List.of());
    }

    static Map<String, Object> fromPackItems(List<TaskRagPackItem> items) {
        List<RetrievedKnowledgeChunk> chunks = items == null ? List.of() : items.stream()
                .filter(Objects::nonNull)
                .map(TaskRagPackItem::toRetrievedKnowledgeChunk)
                .toList();
        return fromChunks(chunks);
    }

    private static Map<String, Object> chunkSummary(RetrievedKnowledgeChunk chunk) {
        if (chunk == null || chunk.getMetadata() == null || chunk.getMetadata().isEmpty()) {
            return Map.of();
        }
        Map<String, Object> metadata = chunk.getMetadata();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("rank", chunk.getRank());
        summary.put("chunkId", chunk.getChunkId());
        summary.put("sourceDocumentId", chunk.getSourceDocumentId());
        copy(metadata, summary, "reranked");
        copy(metadata, summary, "reranker");
        copy(metadata, summary, "rerankerModel");
        copy(metadata, summary, "rerankerEndpoint");
        copy(metadata, summary, "crossEncoderScore");
        copy(metadata, summary, "normalizedCrossEncoderScore");
        copy(metadata, summary, "fusionScoreBeforeRerank");
        copy(metadata, summary, "finalRerankScore");
        copy(metadata, summary, "rerankCandidateCount");
        copy(metadata, summary, "rerankInputIndex");
        copy(metadata, summary, "rerankMissingScore");
        summary.values().removeIf(Objects::isNull);
        return summary;
    }

    private static java.util.Optional<Object> firstValue(List<Map<String, Object>> summaries, String key) {
        return summaries.stream()
                .map(item -> item.get(key))
                .filter(Objects::nonNull)
                .findFirst();
    }

    private static void putIfPresent(Map<String, Object> target,
                                     String targetKey,
                                     Map<String, Object> metadata,
                                     String sourceKey) {
        if (metadata != null && metadata.get(sourceKey) != null) {
            target.put(targetKey, metadata.get(sourceKey));
        }
    }

    private static void copy(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.get(key) != null) {
            target.put(key, source.get(key));
        }
    }
}
