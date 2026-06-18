package com.codeguardian.service.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
final class KnowledgeRetriever {

    private final VectorStore vectorStore;
    private final Bm25Index bm25Index;
    private final List<KnowledgeDocument> documents;
    private final RerankerClient rerankerClient;
    private final RagRerankerProperties rerankerProperties;

    KnowledgeRetriever(VectorStore vectorStore, Bm25Index bm25Index, List<KnowledgeDocument> documents) {
        this(vectorStore, bm25Index, documents, null, null);
    }

    KnowledgeRetriever(VectorStore vectorStore,
                       Bm25Index bm25Index,
                       List<KnowledgeDocument> documents,
                       RerankerClient rerankerClient,
                       RagRerankerProperties rerankerProperties) {
        this.vectorStore = vectorStore;
        this.bm25Index = bm25Index;
        this.documents = documents != null ? documents : List.of();
        this.rerankerClient = rerankerClient;
        this.rerankerProperties = rerankerProperties != null ? rerankerProperties : new RagRerankerProperties();
    }

    List<KnowledgeDocument> search(String query, int topK) {
        if (documents.isEmpty()) {
            return Collections.emptyList();
        }

        List<Document> vectorResults = Collections.emptyList();
        if (vectorStore != null) {
            try {
                vectorResults = vectorStore.similaritySearch(SearchRequest.query(query).withTopK(topK));
            } catch (Exception e) {
                log.debug("Vector search failed (using BM25 only): {}", e.getMessage());
            }
        }

        List<Integer> bm25Indices = bm25Index.search(query, topK);
        return mergeAndRerank(vectorResults, bm25Indices, topK);
    }

    List<RetrievedKnowledgeChunk> searchSnippetChunks(String query, int topK) {
        int requestedTopK = Math.max(1, topK);
        int recallTopK = recallTopK(requestedTopK);
        SearchProfile profile = SearchProfile.from(query);
        Map<String, RetrievalCandidate> candidates = new LinkedHashMap<>();

        List<Document> vectorResults = vectorSnippetResults(query, recallTopK);
        collectVectorCandidates(vectorResults, candidates);

        List<Integer> bm25Indices = bm25Index.search(query, recallTopK);
        collectBm25Candidates(bm25Indices, candidates);

        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        candidates.values().forEach(candidate -> scoreCandidate(candidate, profile));
        List<RetrievalCandidate> preRanked = candidates.values().stream()
                .sorted(fusionComparator())
                .limit(candidateTopK(requestedTopK, candidates.size()))
                .collect(Collectors.toList());
        List<RetrievalCandidate> selected = rerankCandidates(query, preRanked, requestedTopK);
        List<RetrievedKnowledgeChunk> chunks = selected.stream()
                .limit(requestedTopK)
                .map(RetrievalCandidate::toChunk)
                .collect(Collectors.toList());

        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).setRank(i + 1);
        }
        log.info("RAG snippet search fused results: vector={}, bm25={}, selected={}",
                vectorResults.size(), bm25Indices.size(), chunks.size());
        return chunks;
    }

    private int recallTopK(int requestedTopK) {
        int defaultRecallTopK = Math.max(requestedTopK * 2, requestedTopK);
        if (!rerankerEnabled()) {
            return defaultRecallTopK;
        }
        int rerankWindow = Math.max(requestedTopK, rerankerProperties.getCandidateTopK());
        return Math.max(defaultRecallTopK, Math.min(rerankWindow, requestedTopK * 4));
    }

    private int candidateTopK(int requestedTopK, int available) {
        if (!rerankerEnabled()) {
            return requestedTopK;
        }
        int configured = Math.max(requestedTopK, rerankerProperties.getCandidateTopK());
        return Math.max(requestedTopK, Math.min(configured, available));
    }

    private Comparator<RetrievalCandidate> fusionComparator() {
        return Comparator
                .comparingDouble((RetrievalCandidate c) -> c.fusedScore).reversed()
                .thenComparing(c -> c.vectorRank != null ? c.vectorRank : Integer.MAX_VALUE)
                .thenComparing(c -> c.bm25Rank != null ? c.bm25Rank : Integer.MAX_VALUE);
    }

    private List<RetrievalCandidate> rerankCandidates(String query,
                                                      List<RetrievalCandidate> preRanked,
                                                      int requestedTopK) {
        if (!rerankerEnabled() || preRanked == null || preRanked.isEmpty()) {
            return preRanked != null ? preRanked.stream().limit(requestedTopK).collect(Collectors.toList()) : List.of();
        }
        List<RetrievedKnowledgeChunk> chunks = preRanked.stream()
                .map(RetrievalCandidate::toChunk)
                .collect(Collectors.toList());
        RerankResponse response = rerankerClient.rerank(query, chunks);
        if (response == null || !response.hasResults()) {
            return preRanked.stream().limit(requestedTopK).collect(Collectors.toList());
        }

        Map<Integer, RerankResult> resultsByIndex = response.results().stream()
                .collect(Collectors.toMap(RerankResult::index, result -> result, (first, ignored) -> first));
        double maxFusionScore = preRanked.stream()
                .mapToDouble(candidate -> candidate.fusedScore)
                .max()
                .orElse(1.0d);
        List<RetrievalCandidate> reranked = new ArrayList<>();
        Set<Integer> scoredIndexes = new HashSet<>();
        for (int i = 0; i < preRanked.size(); i++) {
            RerankResult result = resultsByIndex.get(i);
            if (result == null) {
                continue;
            }
            RetrievalCandidate candidate = preRanked.get(i);
            double fusionBeforeRerank = candidate.fusedScore;
            double normalizedFusionScore = maxFusionScore > 0 ? fusionBeforeRerank / maxFusionScore : 0.0d;
            double crossEncoderScore = normalizeCrossEncoderScore(result.score());
            double finalScore = rerankerProperties.getCrossEncoderWeight() * crossEncoderScore
                    + rerankerProperties.getFusionWeight() * normalizedFusionScore;

            candidate.fusedScore = finalScore;
            candidate.metadata.put("reranked", true);
            candidate.metadata.put("reranker", "cross_encoder");
            candidate.metadata.put("rerankerModel", response.model());
            candidate.metadata.put("rerankerEndpoint", response.endpoint());
            candidate.metadata.put("crossEncoderScore", result.score());
            candidate.metadata.put("normalizedCrossEncoderScore", crossEncoderScore);
            candidate.metadata.put("fusionScoreBeforeRerank", fusionBeforeRerank);
            candidate.metadata.put("finalRerankScore", finalScore);
            candidate.metadata.put("fusedScore", finalScore);
            candidate.metadata.put("rerankCandidateCount", preRanked.size());
            candidate.metadata.put("rerankInputIndex", i);
            reranked.add(candidate);
            scoredIndexes.add(i);
        }

        List<RetrievalCandidate> fallback = new ArrayList<>();
        for (int i = 0; i < preRanked.size(); i++) {
            if (!scoredIndexes.contains(i)) {
                RetrievalCandidate candidate = preRanked.get(i);
                candidate.metadata.put("reranked", false);
                candidate.metadata.put("reranker", "cross_encoder");
                candidate.metadata.put("rerankMissingScore", true);
                fallback.add(candidate);
            }
        }
        reranked.sort(fusionComparator());
        fallback.sort(fusionComparator());
        reranked.addAll(fallback);
        return reranked.stream().limit(requestedTopK).collect(Collectors.toList());
    }

    private boolean rerankerEnabled() {
        return rerankerClient != null && rerankerClient.isEnabled()
                && rerankerProperties != null
                && rerankerProperties.isEnabled();
    }

    private double normalizeCrossEncoderScore(double score) {
        if (!rerankerProperties.isRawScores()) {
            return clamp(score, 0.0d, 1.0d);
        }
        return 1.0d / (1.0d + Math.exp(-score));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private List<Document> vectorSnippetResults(String query, int recallTopK) {
        if (vectorStore == null) {
            return Collections.emptyList();
        }
        try {
            return vectorStore.similaritySearch(SearchRequest.query(query).withTopK(recallTopK));
        } catch (Exception e) {
            log.warn("Vector snippet search failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private void collectVectorCandidates(List<Document> vectorResults, Map<String, RetrievalCandidate> candidates) {
        for (int i = 0; i < vectorResults.size(); i++) {
            Document vectorDoc = vectorResults.get(i);
            Map<String, Object> metadata = vectorDoc.getMetadata() != null
                    ? new LinkedHashMap<>(vectorDoc.getMetadata())
                    : new LinkedHashMap<>();
            String sourceDocId = stringValue(metadata.getOrDefault("source_doc_id", vectorDoc.getId()));
            KnowledgeDocument sourceDoc = findDocById(sourceDocId);
            String title = firstNonBlank(stringValue(metadata.get("title")),
                    sourceDoc != null ? sourceDoc.getTitle() : null);
            String content = RagTextSanitizer.clean(vectorDoc.getContent());
            if (content == null || content.trim().isEmpty()) {
                continue;
            }
            String key = candidateKey(sourceDocId, vectorDoc.getId());
            RetrievalCandidate candidate = candidates.computeIfAbsent(key,
                    ignored -> RetrievalCandidate.fromVector(vectorDoc.getId(), sourceDocId, title, content, metadata));
            candidate.vectorRank = i + 1;
            candidate.vectorScore = doubleValue(metadata.get("score"));
            candidate.metadata.put("vectorRank", candidate.vectorRank);
            if (candidate.vectorScore != null) {
                candidate.metadata.put("vectorScore", candidate.vectorScore);
            }
        }
    }

    private void collectBm25Candidates(List<Integer> bm25Indices, Map<String, RetrievalCandidate> candidates) {
        for (int i = 0; i < bm25Indices.size(); i++) {
            Integer docIndex = bm25Indices.get(i);
            if (docIndex == null || docIndex < 0 || docIndex >= documents.size()) {
                continue;
            }
            KnowledgeDocument doc = documents.get(docIndex);
            String content = RagTextSanitizer.clean(doc.getContent());
            if (content == null || content.trim().isEmpty()) {
                continue;
            }
            if (content.length() > 800) {
                content = content.substring(0, 800) + "... (truncated)";
            }
            final String candidateContent = content;
            String key = candidateKey(doc.getId(), doc.getId());
            RetrievalCandidate candidate = candidates.computeIfAbsent(key,
                    ignored -> RetrievalCandidate.fromDocument(doc, candidateContent));
            candidate.bm25Rank = i + 1;
            candidate.metadata.put("bm25Rank", candidate.bm25Rank);
            candidate.metadata.putIfAbsent("category", doc.getCategory());
            if (doc.getMetadata() != null) {
                candidate.metadata.putIfAbsent("documentMetadata", new LinkedHashMap<>(doc.getMetadata()));
            }
        }
    }

    private void scoreCandidate(RetrievalCandidate candidate, SearchProfile profile) {
        double score = 0.0;
        if (candidate.vectorRank != null) {
            score += 1.0 / (60 + candidate.vectorRank);
        }
        if (candidate.bm25Rank != null) {
            score += 1.0 / (60 + candidate.bm25Rank);
        }

        boolean languageMatched = profile.matchesLanguage(candidate);
        boolean categoryMatched = profile.matchesCategory(candidate);
        boolean keywordMatched = profile.matchesKeyword(candidate);
        if (languageMatched) {
            score += 0.05;
        }
        if (categoryMatched) {
            score += 0.08;
        }
        if (keywordMatched) {
            score += 0.08;
        }
        candidate.fusedScore = score;
        candidate.retrievalMode = candidate.vectorRank != null && candidate.bm25Rank != null
                ? "VECTOR_BM25_FUSED"
                : (candidate.vectorRank != null ? "VECTOR_ONLY" : "BM25_ONLY");
        candidate.metadata.put("fusedScore", score);
        candidate.metadata.put("retrievalMode", candidate.retrievalMode);
        candidate.metadata.put("metadataLanguageMatched", languageMatched);
        candidate.metadata.put("metadataCategoryMatched", categoryMatched);
        candidate.metadata.put("keywordMatched", keywordMatched);
    }

    private List<KnowledgeDocument> mergeAndRerank(List<Document> vectorDocs, List<Integer> bm25Indices, int topK) {
        Map<String, Double> rrfScores = new HashMap<>();
        int k = 60;

        if (vectorDocs != null) {
            for (int i = 0; i < vectorDocs.size(); i++) {
                Document doc = vectorDocs.get(i);
                String id = (String) doc.getMetadata().getOrDefault("source_doc_id", doc.getId());
                rrfScores.put(id, rrfScores.getOrDefault(id, 0.0) + 1.0 / (k + i + 1));
            }
        }

        for (int i = 0; i < bm25Indices.size(); i++) {
            if (bm25Indices.get(i) < documents.size()) {
                String id = documents.get(bm25Indices.get(i)).getId();
                rrfScores.put(id, rrfScores.getOrDefault(id, 0.0) + 1.0 / (k + i + 1));
            }
        }

        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> findDocById(e.getKey()))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private KnowledgeDocument findDocById(String id) {
        if (id == null) {
            return null;
        }
        return documents.stream().filter(d -> id.equals(d.getId())).findFirst().orElse(null);
    }

    private String candidateKey(String sourceDocId, String chunkId) {
        if (sourceDocId != null && !sourceDocId.isBlank()) {
            return sourceDocId;
        }
        return chunkId != null && !chunkId.isBlank() ? chunkId : UUID.randomUUID().toString();
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null ? second : "";
    }

    private String stringValue(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private Double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
