package com.codeguardian.service.rag;

import java.util.ArrayList;
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
    private int documentCount;
    private double avgDocLength;

    void rebuild(List<KnowledgeDocument> documents) {
        invertedIndex.clear();
        docTermFreqs.clear();
        docLengths.clear();
        documentCount = documents != null ? documents.size() : 0;
        avgDocLength = 0;

        if (documents == null || documents.isEmpty()) {
            return;
        }

        long totalLength = 0;
        for (int i = 0; i < documents.size(); i++) {
            totalLength += add(documents.get(i), i);
        }
        avgDocLength = (double) totalLength / documents.size();
    }

    List<Integer> search(String query, int topK) {
        if (documentCount == 0 || avgDocLength <= 0) {
            return List.of();
        }
        List<String> queryTerms = tokenize(query != null ? query.toLowerCase() : "");
        Map<Integer, Double> scores = new HashMap<>();

        for (String term : queryTerms) {
            List<Integer> docIndices = invertedIndex.get(term);
            if (docIndices == null) {
                continue;
            }
            double idf = Math.log(1 + (documentCount - docIndices.size() + 0.5) / (docIndices.size() + 0.5));
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

    private long add(KnowledgeDocument doc, int index) {
        String text = ((doc.getTitle() != null ? doc.getTitle() : "") + " "
                + (doc.getContent() != null ? doc.getContent() : "")).toLowerCase();
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

    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("[\\u4e00-\\u9fa5]|[a-zA-Z0-9]+");
        java.util.regex.Matcher matcher = pattern.matcher(text != null ? text : "");
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }
}
