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
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeRetrieverChunkFusionTest {

    @Mock
    private VectorStore vectorStore;

    @Test
    void searchSnippetChunks_ShouldKeepSameDocumentVectorChunksIndependent() {
        KnowledgeDocument sourceDoc = KnowledgeDocument.builder()
                .id("doc-1")
                .title("Java rules")
                .content("Full source document")
                .category("CODE_STYLE")
                .build();
        Document firstChunk = new Document("chunk-1", "Use CamelCase for classes.",
                Map.of("source_doc_id", "doc-1", "title", "Java rules"));
        Document secondChunk = new Document("chunk-2", "Use lowerCamelCase for methods.",
                Map.of("source_doc_id", "doc-1", "title", "Java rules"));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(firstChunk, secondChunk));

        KnowledgeRetriever retriever = new KnowledgeRetriever(vectorStore, new Bm25Index(), List.of(sourceDoc));

        List<RetrievedKnowledgeChunk> chunks = retriever.searchSnippetChunks("CamelCase", 5);

        assertEquals(List.of("chunk-1", "chunk-2"),
                chunks.stream().map(RetrievedKnowledgeChunk::getChunkId).collect(Collectors.toList()));
        assertTrue(chunks.stream().allMatch(chunk -> "doc-1".equals(chunk.getSourceDocumentId())));
    }

    @Test
    void searchSnippetChunks_ShouldNotFuseVectorAndBm25_WhenSameDocumentButDifferentChunksMatch() {
        KnowledgeDocument documentLevelBm25Chunk = KnowledgeDocument.builder()
                .id("doc-1")
                .title("Java Naming")
                .content("Use CamelCase for Java class names.")
                .category("CODE_STYLE")
                .build();
        Bm25Index bm25Index = new Bm25Index();
        bm25Index.rebuild(List.of(documentLevelBm25Chunk));

        Document vectorChunk = new Document("chunk-vector-1", "Use CamelCase for Java class names.",
                Map.of("source_doc_id", "doc-1", "title", "Java Naming", "category", "CODE_STYLE"));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(vectorChunk));

        KnowledgeRetriever retriever = new KnowledgeRetriever(vectorStore, bm25Index, List.of(documentLevelBm25Chunk));

        List<RetrievedKnowledgeChunk> chunks = retriever.searchSnippetChunks("CamelCase", 5);
        Map<String, RetrievedKnowledgeChunk> byChunkId = chunks.stream()
                .collect(Collectors.toMap(RetrievedKnowledgeChunk::getChunkId, Function.identity()));

        assertEquals("VECTOR_ONLY", byChunkId.get("chunk-vector-1").getRetrievalMode());
        assertEquals("BM25_ONLY", byChunkId.get("doc-1").getRetrievalMode());
        assertFalse(chunks.stream().anyMatch(chunk -> "VECTOR_BM25_FUSED".equals(chunk.getRetrievalMode())));
    }

    @Test
    void searchSnippetChunks_ShouldFuseVectorAndBm25_WhenSameChunkMatchesBoth() {
        KnowledgeDocument bm25Chunk = KnowledgeDocument.builder()
                .id("chunk-shared-1")
                .title("Java Naming")
                .content("Use CamelCase for Java class names.")
                .category("CODE_STYLE")
                .metadata(Map.of("source_doc_id", "doc-1"))
                .build();
        Bm25Index bm25Index = new Bm25Index();
        bm25Index.rebuild(List.of(bm25Chunk));

        Document vectorChunk = new Document("chunk-shared-1", "Use CamelCase for Java class names.",
                Map.of("source_doc_id", "doc-1", "title", "Java Naming", "category", "CODE_STYLE"));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(vectorChunk));

        KnowledgeRetriever retriever = new KnowledgeRetriever(vectorStore, bm25Index, List.of(bm25Chunk));

        List<RetrievedKnowledgeChunk> chunks = retriever.searchSnippetChunks("CamelCase", 5);

        assertEquals(1, chunks.size());
        RetrievedKnowledgeChunk chunk = chunks.get(0);
        assertEquals("chunk-shared-1", chunk.getChunkId());
        assertEquals("doc-1", chunk.getSourceDocumentId());
        assertEquals("VECTOR_BM25_FUSED", chunk.getRetrievalMode());
        assertEquals(1, chunk.getMetadata().get("vectorRank"));
        assertEquals(1, chunk.getMetadata().get("bm25Rank"));
    }
}
