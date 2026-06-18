package com.codeguardian.service.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeRetrieverRerankerTest {

    @Mock
    private VectorStore vectorStore;

    @Mock
    private RerankerClient rerankerClient;

    @Test
    void searchSnippetChunks_ShouldUseCrossEncoderScore_WhenRerankerIsAvailable() {
        KnowledgeDocument styleDoc = KnowledgeDocument.builder()
                .id("style")
                .title("Style Rules")
                .content("Use clear class names.")
                .category("CODE_STYLE")
                .build();
        KnowledgeDocument securityDoc = KnowledgeDocument.builder()
                .id("security")
                .title("SQL Security")
                .content("Use prepared statements for database access.")
                .category("SECURITY")
                .build();
        Bm25Index bm25Index = new Bm25Index();
        bm25Index.rebuild(List.of(styleDoc, securityDoc));

        Document styleChunk = new Document("chunk-style", "Use clear class names.",
                Map.of("source_doc_id", "style", "title", "Style Rules", "category", "CODE_STYLE"));
        Document securityChunk = new Document("chunk-security", "Use prepared statements for database access.",
                Map.of("source_doc_id", "security", "title", "SQL Security", "category", "SECURITY"));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(styleChunk, securityChunk));

        RagRerankerProperties properties = new RagRerankerProperties();
        properties.setCandidateTopK(4);
        when(rerankerClient.isEnabled()).thenReturn(true);
        when(rerankerClient.rerank(eq("unmatched query"), anyList())).thenAnswer(invocation -> {
            List<RetrievedKnowledgeChunk> chunks = invocation.getArgument(1);
            return new RerankResponse("test-reranker", "http://reranker/rerank", List.of(
                    new RerankResult(0, chunks.get(0).getChunkId(), 0.10d),
                    new RerankResult(1, chunks.get(1).getChunkId(), 0.99d)
            ));
        });

        KnowledgeRetriever retriever = new KnowledgeRetriever(
                vectorStore,
                bm25Index,
                List.of(styleDoc, securityDoc),
                rerankerClient,
                properties
        );

        List<RetrievedKnowledgeChunk> chunks = retriever.searchSnippetChunks("unmatched query", 2);

        assertEquals(2, chunks.size());
        assertEquals("chunk-security", chunks.get(0).getChunkId());
        assertEquals("test-reranker", chunks.get(0).getMetadata().get("rerankerModel"));
        assertEquals(0.99d, (Double) chunks.get(0).getMetadata().get("crossEncoderScore"));
        assertTrue(Boolean.TRUE.equals(chunks.get(0).getMetadata().get("reranked")));
        assertTrue(chunks.get(0).getMetadata().containsKey("fusionScoreBeforeRerank"));
    }
}
