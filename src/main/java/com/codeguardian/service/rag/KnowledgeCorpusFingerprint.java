package com.codeguardian.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

class KnowledgeCorpusFingerprint {

    private KnowledgeCorpusFingerprint() {
    }

    static String calculate(List<KnowledgeDocument> documents, ObjectMapper objectMapper) {
        List<KnowledgeDocument> snapshot = new ArrayList<>(documents != null ? documents : List.of());
        snapshot.sort(Comparator.comparing(doc -> safeString(doc.getId())));

        List<Map<String, Object>> payload = snapshot.stream()
                .map(KnowledgeCorpusFingerprint::toPayload)
                .collect(Collectors.toList());
        try {
            String serialized = objectMapper.writeValueAsString(payload);
            return sha256Hex(serialized != null ? serialized : payload.toString());
        } catch (Exception e) {
            return sha256Hex(payload.toString());
        }
    }

    private static Map<String, Object> toPayload(KnowledgeDocument doc) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", doc.getId());
        payload.put("title", doc.getTitle());
        payload.put("category", doc.getCategory());
        payload.put("content", doc.getContent());
        payload.put("solution", doc.getSolution());
        payload.put("metadata", doc.getMetadata() != null ? new TreeMap<>(doc.getMetadata()) : Map.of());
        payload.put("contentType", doc.getContentType());
        payload.put("fileSize", doc.getFileSize());
        payload.put("minioObjectName", doc.getMinioObjectName());
        return payload;
    }

    private static String safeString(String value) {
        return value != null ? value : "";
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encoded = digest.digest((value != null ? value : "").getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(encoded.length * 2);
            for (byte b : encoded) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
