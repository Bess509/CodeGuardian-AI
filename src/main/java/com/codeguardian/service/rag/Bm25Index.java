package com.codeguardian.service.rag;

import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

class Bm25Index {

    private static final double K1 = 1.5;
    private static final double B = 0.75;

    private final Map<String, List<Integer>> invertedIndex = new HashMap<>();
    private final List<Map<String, Integer>> docTermFreqs = new ArrayList<>();
    private final List<Integer> docLengths = new ArrayList<>();
    private final List<IndexedChunk> chunks = new ArrayList<>();
    private int chunkCount;
    private double avgDocLength;

    void rebuild(List<KnowledgeDocument> documents) {
        List<IndexedChunk> indexedChunks = documents != null
                ? documents.stream().map(this::fromKnowledgeDocument).collect(Collectors.toList())
                : List.of();
        rebuildIndexedChunks(indexedChunks);
    }

    void rebuildChunks(List<Document> documents) {
        List<IndexedChunk> indexedChunks = documents != null
                ? documents.stream().map(this::fromSpringDocument).collect(Collectors.toList())
                : List.of();
        rebuildIndexedChunks(indexedChunks);
    }

    IndexedChunk get(Integer index) {
        if (index == null || index < 0 || index >= chunks.size()) {
            return null;
        }
        return chunks.get(index);
    }

    private void rebuildIndexedChunks(List<IndexedChunk> indexedChunks) {
        invertedIndex.clear();
        docTermFreqs.clear();
        docLengths.clear();
        chunks.clear();
        if (indexedChunks != null) {
            chunks.addAll(indexedChunks);
        }
        chunkCount = chunks.size();
        avgDocLength = 0;

        if (chunks.isEmpty()) {
            return;
        }

        long totalLength = 0;
        for (int i = 0; i < chunks.size(); i++) {
            totalLength += add(chunks.get(i), i);
        }
        avgDocLength = (double) totalLength / chunks.size();
    }

    List<Integer> search(String query, int topK) {
        if (chunkCount == 0 || avgDocLength <= 0 || topK <= 0) {
            return List.of();
        }
        List<String> queryTerms = tokenize(query != null ? query.toLowerCase() : "");
        Map<Integer, Double> scores = new HashMap<>();

        for (String term : queryTerms) {
            List<Integer> docIndices = invertedIndex.get(term);
            if (docIndices == null) {
                continue;
            }
            double idf = Math.log(1 + (chunkCount - docIndices.size() + 0.5) / (docIndices.size() + 0.5));
            for (Integer docIdx : docIndices) {
                if (docIdx == null || docIdx < 0 || docIdx >= docTermFreqs.size()) {
                    continue;
                }
                int freq = docTermFreqs.get(docIdx).getOrDefault(term, 0);
                int docLen = docLengths.get(docIdx);
                double numerator = freq * (K1 + 1);
                double denominator = freq + K1 * (1 - B + B * (docLen / avgDocLength));
                scores.put(docIdx, scores.getOrDefault(docIdx, 0.0) + idf * numerator / denominator);
            }
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue().reversed())
                .limit(topK)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    private long add(IndexedChunk chunk, int index) {
        String text = String.join(" ",
                safe(chunk.title()),
                safe(metadataValue(chunk.metadata(), "heading_path", "headingPath")),
                safe(metadataValue(chunk.metadata(), "rule_ids", "ruleIds")),
                safe(chunk.category()),
                safe(chunk.content())
        ).toLowerCase();
        List<String> terms = tokenize(text);

        Map<String, Integer> freqs = new HashMap<>();
        for (String term : terms) {
            freqs.put(term, freqs.getOrDefault(term, 0) + 1);
        }

        docTermFreqs.add(freqs);
        docLengths.add(terms.size());

        for (String term : freqs.keySet()) {
            invertedIndex.computeIfAbsent(term, ignored -> new ArrayList<>()).add(index);
        }
        return terms.size();
    }

    private IndexedChunk fromKnowledgeDocument(KnowledgeDocument doc) {
        Map<String, Object> metadata = doc.getMetadata() != null
                ? new LinkedHashMap<>(doc.getMetadata())
                : new LinkedHashMap<>();
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
        metadata.putIfAbsent("chunk_id", chunkId);
        metadata.putIfAbsent("source_doc_id", sourceDocumentId);
        metadata.putIfAbsent("title", doc.getTitle());
        metadata.putIfAbsent("category", doc.getCategory());
        return new IndexedChunk(
                chunkId,
                sourceDocumentId,
                doc.getTitle(),
                doc.getContent(),
                doc.getCategory(),
                metadata
        );
    }

    private IndexedChunk fromSpringDocument(Document document) {
        Map<String, Object> metadata = document.getMetadata() != null
                ? new LinkedHashMap<>(document.getMetadata())
                : new LinkedHashMap<>();
        String chunkId = firstNonBlank(
                stringValue(metadata.get("chunk_id")),
                stringValue(metadata.get("chunkId")),
                document.getId()
        );
        String sourceDocumentId = firstNonBlank(
                stringValue(metadata.get("source_doc_id")),
                stringValue(metadata.get("document_id")),
                stringValue(metadata.get("sourceDocumentId")),
                chunkId
        );
        String title = stringValue(metadata.get("title"));
        String category = stringValue(metadata.get("category"));
        metadata.putIfAbsent("chunk_id", chunkId);
        metadata.putIfAbsent("source_doc_id", sourceDocumentId);
        return new IndexedChunk(
                chunkId,
                sourceDocumentId,
                title,
                document.getContent(),
                category,
                metadata
        );
    }

    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("[\\u4e00-\\u9fa5]|[a-zA-Z0-9]+");
        java.util.regex.Matcher matcher = pattern.matcher(text != null ? text : "");
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private String metadataValue(Map<String, Object> metadata, String... keys) {
        if (metadata == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            Object value = metadata.get(key);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return "";
    }

    private String firstNonBlank(String... values) {
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

    private String stringValue(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private String safe(String value) {
        return value != null ? value : "";
    }

    record IndexedChunk(String chunkId,
                        String sourceDocumentId,
                        String title,
                        String content,
                        String category,
                        Map<String, Object> metadata) {
    }
}
