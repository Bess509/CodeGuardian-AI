package com.codeguardian.service.rag;

public record RerankResult(int index, String chunkId, double score) {
}
