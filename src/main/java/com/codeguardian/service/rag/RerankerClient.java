package com.codeguardian.service.rag;

import java.util.List;

public interface RerankerClient {

    boolean isEnabled();

    RerankResponse rerank(String query, List<RetrievedKnowledgeChunk> chunks);
}
