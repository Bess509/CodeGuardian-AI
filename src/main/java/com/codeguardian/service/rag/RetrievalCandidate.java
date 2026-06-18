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
        candidate.metadata.putIfAbsent("chunk_id", chunkId);
        candidate.sourceRef = sourceRef(sourceDocumentId, chunkId);
        return candidate;
    }

    static RetrievalCandidate fromDocument(KnowledgeDocument doc, String content) {
        RetrievalCandidate candidate = new RetrievalCandidate();
        Map<String, Object> metadata = doc.getMetadata() != null ? new LinkedHashMap<>(doc.getMetadata()) : new LinkedHashMap<>();
        String chunkId = firstNonBlank(
                stringValue(metadata.get("chunk_id")),
                stringValue(metadata.get("chunkId")),
                doc.getId()
        );
        String sourceDocumentId = firstNonBlank(
                stringValue(metadata.get("source_doc_id")),
                stringValue(metadata.get("document_id")),
                stringValue(metadata.get("sourceDocumentId")),
                doc.getId()
        );
        candidate.chunkId = chunkId;
        candidate.sourceDocumentId = sourceDocumentId;
        candidate.title = doc.getTitle();
        candidate.content = content;
        candidate.metadata = metadata;
        candidate.metadata.put("source_doc_id", sourceDocumentId);
        candidate.metadata.put("chunk_id", chunkId);
        candidate.metadata.put("title", doc.getTitle());
        candidate.metadata.put("category", doc.getCategory());
        candidate.sourceRef = sourceRef(sourceDocumentId, chunkId);
        return candidate;
    }

    static RetrievalCandidate fromBm25Chunk(Bm25Index.IndexedChunk chunk) {
        RetrievalCandidate candidate = new RetrievalCandidate();
        candidate.chunkId = chunk.chunkId();
        candidate.sourceDocumentId = chunk.sourceDocumentId();
        candidate.title = chunk.title();
        candidate.content = RagTextSanitizer.clean(chunk.content());
        candidate.metadata = chunk.metadata() != null ? new LinkedHashMap<>(chunk.metadata()) : new LinkedHashMap<>();
        candidate.metadata.put("source_doc_id", chunk.sourceDocumentId());
        candidate.metadata.put("chunk_id", chunk.chunkId());
        candidate.metadata.putIfAbsent("title", chunk.title());
        candidate.metadata.putIfAbsent("category", chunk.category());
        candidate.sourceRef = sourceRef(chunk.sourceDocumentId(), chunk.chunkId());
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

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String stringValue(Object value) {
        return value != null ? String.valueOf(value) : null;
    }
}
