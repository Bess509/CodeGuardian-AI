package com.codeguardian.service.rag;

import com.codeguardian.repository.KnowledgeDocumentRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class KnowledgeBaseServiceTest {

    @Mock
    private EmbeddingModel embeddingModel;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private VectorStore vectorStore;
    @Mock
    private KnowledgeDocumentRepository repository;
    @Mock
    private MinioStorageService minioStorageService;
    @Mock
    private DocumentParsingService documentParsingService;
    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private KnowledgeBaseService knowledgeBaseService;

    @BeforeEach
    void setUp() throws Exception {
        // Prepare data for BM25 (loaded from DB)
        KnowledgeDocument fullDoc = KnowledgeDocument.builder()
                .id("doc1")
                .title("Java Naming Conventions")
                .content("Full document content with many rules. Rule 1: Use CamelCase. Rule 2: ...")
                .category("CODE_STYLE")
                .build();

        when(jdbcTemplate.queryForObject(any(String.class), eq(Integer.class))).thenReturn(0);
        doReturn(Collections.emptyList()).when(objectMapper).readValue(any(InputStream.class), any(TypeReference.class));
        when(repository.findAll()).thenReturn(List.of(fullDoc));

        java.lang.reflect.Field vectorStoreField = KnowledgeBaseService.class.getDeclaredField("vectorStore");
        vectorStoreField.setAccessible(true);
        vectorStoreField.set(knowledgeBaseService, vectorStore);
        
        // Trigger init to build BM25 index
        knowledgeBaseService.init();
    }

    @Test
    void searchSnippets_ShouldReturnStrings_WhenVectorSearchHits() {
        // Mock Vector Store
        Document vectorChunk = new Document("chunk1", "Snippet Content", Map.of("title", "Snippet Title"));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(vectorChunk));

        // Execute
        List<String> snippets = knowledgeBaseService.searchSnippets("query", 3);

        // Assert
        assertEquals(1, snippets.size());
        assertTrue(snippets.get(0).contains("【Snippet Title】"));
        assertTrue(snippets.get(0).contains("Snippet Content"));
    }

    @Test
    void searchSnippetChunks_ShouldReturnTraceableMetadata_WhenVectorSearchHits() {
        Document vectorChunk = new Document("chunk1", "Snippet Content",
                Map.of("title", "Snippet Title", "source_doc_id", "doc1", "score", 0.91d));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(vectorChunk));

        List<RetrievedKnowledgeChunk> chunks = knowledgeBaseService.searchSnippetChunks("query", 3);

        assertEquals(1, chunks.size());
        RetrievedKnowledgeChunk chunk = chunks.get(0);
        assertEquals("chunk1", chunk.getChunkId());
        assertEquals("doc1", chunk.getSourceDocumentId());
        assertEquals("Snippet Title", chunk.getTitle());
        assertEquals("VECTOR_ONLY", chunk.getRetrievalMode());
        assertEquals(1, chunk.getRank());
        assertEquals("knowledge://document/doc1#chunk=chunk1", chunk.getSourceRef());
        assertEquals(0.91d, (Double) chunk.getMetadata().get("vectorScore"));
        assertTrue(chunk.toPromptSnippet().contains("Snippet Content"));
    }

    @Test
    void searchSnippetChunks_ShouldFallbackToHybridWithDocumentRef_WhenVectorSearchMisses() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(Collections.emptyList());

        List<RetrievedKnowledgeChunk> chunks = knowledgeBaseService.searchSnippetChunks("CamelCase", 3);

        assertEquals(1, chunks.size());
        RetrievedKnowledgeChunk chunk = chunks.get(0);
        assertEquals("doc1", chunk.getChunkId());
        assertEquals("doc1", chunk.getSourceDocumentId());
        assertEquals("BM25_ONLY", chunk.getRetrievalMode());
        assertEquals("knowledge://document/doc1#chunk=doc1", chunk.getSourceRef());
        assertTrue(chunk.toPromptSnippet().contains("Java Naming Conventions"));
    }

    @Test
    void searchSnippetChunks_ShouldFuseVectorAndBm25_WhenSameSourceMatchesBoth() {
        Document vectorChunk = new Document("chunk1", "Use CamelCase for Java class names.",
                Map.of("title", "Snippet Title", "source_doc_id", "doc1", "score", 0.91d));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(vectorChunk));

        List<RetrievedKnowledgeChunk> chunks = knowledgeBaseService.searchSnippetChunks(
                "Language: Java\nRule Categories: CODE_STYLE\nRisk Keywords: CamelCase", 3);

        assertEquals(1, chunks.size());
        RetrievedKnowledgeChunk chunk = chunks.get(0);
        assertEquals("VECTOR_BM25_FUSED", chunk.getRetrievalMode());
        assertEquals(1, chunk.getMetadata().get("vectorRank"));
        assertEquals(1, chunk.getMetadata().get("bm25Rank"));
        assertTrue(Boolean.TRUE.equals(chunk.getMetadata().get("metadataLanguageMatched")));
        assertTrue(Boolean.TRUE.equals(chunk.getMetadata().get("metadataCategoryMatched")));
        assertTrue(Boolean.TRUE.equals(chunk.getMetadata().get("keywordMatched")));
        assertEquals((1.0d / 61) + (1.0d / 61) + 0.003d + 0.006d + 0.008d,
                (Double) chunk.getMetadata().get("fusedScore"),
                0.000001d);
    }

    @Test
    void search_ShouldReturnDoc_WhenVectorSearchHits() {
        // Mock Vector Store to return a specific chunk
        Document vectorChunk = new Document("chunk1", "Rule 1: Use CamelCase", Map.of("source_doc_id", "doc1"));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(vectorChunk));

        // Execute search
        List<KnowledgeDocument> results = knowledgeBaseService.search("naming", 3);

        // Assertions
        assertEquals(1, results.size(), "Should return 1 result");
        assertTrue(results.get(0).getContent().contains("Use CamelCase"));
    }

    @Test
    void getCorpusFingerprint_ShouldChange_WhenKnowledgeCorpusChanges() throws Exception {
        String first = knowledgeBaseService.getCorpusFingerprint();

        java.lang.reflect.Field documentsField = KnowledgeBaseService.class.getDeclaredField("documents");
        documentsField.setAccessible(true);
        documentsField.set(knowledgeBaseService, List.of(
                KnowledgeDocument.builder()
                        .id("doc1")
                        .title("Java Naming Conventions")
                        .content("Full document content with many rules. Rule 1: Use CamelCase. Rule 2: ...")
                        .category("CODE_STYLE")
                        .build(),
                KnowledgeDocument.builder()
                        .id("doc2")
                        .title("Security Rules")
                        .content("Use prepared statements for SQL queries.")
                        .category("SECURITY")
                        .build()
        ));

        String second = knowledgeBaseService.getCorpusFingerprint();

        assertNotEquals(first, second);
    }
}
