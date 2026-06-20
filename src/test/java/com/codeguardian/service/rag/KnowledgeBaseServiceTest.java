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
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
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
    private KnowledgeChunkRebuildService chunkRebuildService;
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
    void searchSnippetChunks_ShouldNotFuseVectorAndBm25_WhenDifferentChunksMatchSameSource() {
        Document vectorChunk = new Document("chunk1", "Use CamelCase for Java class names.",
                Map.of("title", "Snippet Title", "source_doc_id", "doc1", "score", 0.91d));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(vectorChunk));

        List<RetrievedKnowledgeChunk> chunks = knowledgeBaseService.searchSnippetChunks(
                "Language: Java\nRule Categories: CODE_STYLE\nRisk Keywords: CamelCase", 3);

        assertEquals(2, chunks.size());
        RetrievedKnowledgeChunk vectorResult = chunks.stream()
                .filter(chunk -> "chunk1".equals(chunk.getChunkId()))
                .findFirst()
                .orElseThrow();
        RetrievedKnowledgeChunk bm25Result = chunks.stream()
                .filter(chunk -> "doc1".equals(chunk.getChunkId()))
                .findFirst()
                .orElseThrow();
        assertEquals("VECTOR_ONLY", vectorResult.getRetrievalMode());
        assertEquals("BM25_ONLY", bm25Result.getRetrievalMode());
        assertEquals(1, vectorResult.getMetadata().get("vectorRank"));
        assertEquals(1, bm25Result.getMetadata().get("bm25Rank"));
        assertTrue(Boolean.TRUE.equals(vectorResult.getMetadata().get("metadataLanguageMatched")));
        assertTrue(Boolean.TRUE.equals(vectorResult.getMetadata().get("metadataCategoryMatched")));
        assertTrue(Boolean.TRUE.equals(vectorResult.getMetadata().get("keywordMatched")));
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

    @Test
    void uploadDocument_ShouldRebuildKnowledgeChunksAfterSavingDocument() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "code-style.pdf",
                "application/pdf",
                "CG-CODE-001 Use clear class names.".getBytes()
        );
        when(minioStorageService.uploadFile(file)).thenReturn("uploads/code-style.pdf");
        when(minioStorageService.getBucketName()).thenReturn("code-review");
        when(documentParsingService.parse(file)).thenReturn(ParsedKnowledgeDocument.builder()
                .content("""
                        # Code Style

                        CG-CODE-001 Use clear class names.
                        Class names should reveal responsibilities.
                        """)
                .parser("tika")
                .parserStrategy("tika_fallback")
                .metadata(Map.of("structured", false))
                .build());
        java.lang.reflect.Field documentsField = KnowledgeBaseService.class.getDeclaredField("documents");
        documentsField.setAccessible(true);
        documentsField.set(knowledgeBaseService, new ArrayList<>(knowledgeBaseService.getAllDocuments()));

        knowledgeBaseService.uploadDocument(file);

        verify(repository).save(argThat(document ->
                "code-style.pdf".equals(document.getTitle())
                        && document.getContent().contains("CG-CODE-001")
                        && "uploads/code-style.pdf".equals(document.getMinioObjectName())));
        verify(chunkRebuildService).rebuildDocumentChunks(argThat(document ->
                "code-style.pdf".equals(document.getTitle())
                        && document.getContent().contains("CG-CODE-001")));
    }

    @Test
    void deleteDocument_ShouldDeletePersistedChunksForDocument() {
        KnowledgeDocument uploaded = KnowledgeDocument.builder()
                .id("uploaded-doc")
                .title("Uploaded Rules")
                .content("CG-CODE-001 Use clear names.")
                .minioObjectName("uploads/rules.pdf")
                .build();
        when(repository.findById("uploaded-doc")).thenReturn(Optional.of(uploaded));

        knowledgeBaseService.deleteDocument("uploaded-doc");

        verify(minioStorageService).removeFile("uploads/rules.pdf");
        verify(repository).deleteById("uploaded-doc");
        verify(chunkRebuildService).deleteDocumentChunks("uploaded-doc");
    }
}
