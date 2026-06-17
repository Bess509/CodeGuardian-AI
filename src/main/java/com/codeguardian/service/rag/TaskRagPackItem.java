package com.codeguardian.service.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskRagPackItem {

    private String chunkId;
    private String sourceDocumentId;
    private String title;
    private String content;
    private String sourceRef;
    private String retrievalMode;
    private Integer rank;
    private Double score;
    private Map<String, Object> metadata;

    static TaskRagPackItem from(RetrievedKnowledgeChunk chunk) {
        if (chunk == null) {
            return null;
        }
        return TaskRagPackItem.builder()
                .chunkId(chunk.getChunkId())
                .sourceDocumentId(chunk.getSourceDocumentId())
                .title(chunk.getTitle())
                .content(chunk.getContent())
                .sourceRef(chunk.getSourceRef())
                .retrievalMode(chunk.getRetrievalMode())
                .rank(chunk.getRank())
                .score(chunk.getScore())
                .metadata(chunk.getMetadata() != null ? new LinkedHashMap<>(chunk.getMetadata()) : new LinkedHashMap<>())
                .build();
    }

    RetrievedKnowledgeChunk toRetrievedKnowledgeChunk() {
        return RetrievedKnowledgeChunk.builder()
                .chunkId(chunkId)
                .sourceDocumentId(sourceDocumentId)
                .title(title)
                .content(content)
                .sourceRef(sourceRef)
                .retrievalMode(retrievalMode)
                .rank(rank)
                .score(score)
                .metadata(metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>())
                .build();
    }

    Map<String, Object> toSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        put(summary, "rank", rank);
        put(summary, "chunkId", chunkId);
        put(summary, "sourceDocumentId", sourceDocumentId);
        put(summary, "title", title);
        put(summary, "sourceRef", sourceRef);
        put(summary, "retrievalMode", retrievalMode);
        put(summary, "score", score);
        if (metadata != null && metadata.get("fusedScore") != null) {
            put(summary, "fusedScore", metadata.get("fusedScore"));
        }
        return summary;
    }

    private void put(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
