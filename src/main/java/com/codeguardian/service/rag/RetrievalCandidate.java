package com.codeguardian.service.rag;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

class RetrievalCandidate {

    String chunkId;
    String sourceDocumentId;
    String title;
    String content;
    String sourceRef;
    String retrievalMode;
    Integer vectorRank;
    Integer bm25Rank;
    Double vectorScore;
    double fusedScore;
    Map<String, Object> metadata = new LinkedHashMap<>();

    static RetrievalCandidate fromVector(String chunkId,
                                         String sourceDocumentId,
                                         String title,
                                         String content,
                                         Map<String, Object> metadata) {
        RetrievalCandidate candidate = new RetrievalCandidate();
        candidate.chunkId = chunkId;
        candidate.sourceDocumentId = sourceDocumentId;
        candidate.title = title;
        candidate.content = content;
        candidate.metadata = metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>();
        candidate.metadata.putIfAbsent("source_doc_id", sourceDocumentId);
        candidate.sourceRef = sourceRef(sourceDocumentId, chunkId);
        return candidate;
    }

    static RetrievalCandidate fromDocument(KnowledgeDocument doc, String content) {
        RetrievalCandidate candidate = new RetrievalCandidate();
        candidate.chunkId = doc.getId();
        candidate.sourceDocumentId = doc.getId();
        candidate.title = doc.getTitle();
        candidate.content = content;
        candidate.metadata = doc.getMetadata() != null ? new LinkedHashMap<>(doc.getMetadata()) : new LinkedHashMap<>();
        candidate.metadata.put("source_doc_id", doc.getId());
        candidate.metadata.put("title", doc.getTitle());
        candidate.metadata.put("category", doc.getCategory());
        candidate.sourceRef = sourceRef(doc.getId(), doc.getId());
        return candidate;
    }

    RetrievedKnowledgeChunk toChunk() {
        return RetrievedKnowledgeChunk.builder()
                .chunkId(chunkId)
                .sourceDocumentId(sourceDocumentId)
                .title(title)
                .content(content)
                .sourceRef(sourceRef)
                .retrievalMode(retrievalMode)
                .score(fusedScore)
                .metadata(metadata)
                .build();
    }

    String searchableText() {
        return String.join("\n",
                        title != null ? title : "",
                        content != null ? content : "",
                        metadata != null ? metadata.toString() : "")
                .toLowerCase(Locale.ROOT);
    }

    private static String sourceRef(String sourceDocId, String chunkId) {
        String docRef = sourceDocId != null && !sourceDocId.isBlank() ? sourceDocId : "unknown";
        String chunkRef = chunkId != null && !chunkId.isBlank() ? chunkId : docRef;
        return "knowledge://document/" + docRef + "#chunk=" + chunkRef;
    }
}
