package com.codeguardian.service.rag;

import com.codeguardian.entity.KnowledgeChunk;
import com.codeguardian.repository.KnowledgeChunkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeChunkRebuildServiceTest {

    @Mock
    private KnowledgeChunkRepository chunkRepository;

    private ChunkingProperties properties;
    private KnowledgeChunkRebuildService service;

    @BeforeEach
    void setUp() {
        properties = new ChunkingProperties();
        service = new KnowledgeChunkRebuildService(chunkRepository, properties);
    }

    @Test
    void chunksStaleShouldReturnTrueWhenDocumentHasNoChunks() {
        KnowledgeDocument document = document("Use prepared statements.");
        when(chunkRepository.findByDocumentIdOrderByChunkIndexAsc("doc-1")).thenReturn(List.of());

        assertTrue(service.chunksStale(document));
    }

    @Test
    void chunksStaleShouldReturnFalseWhenStoredChunkMatchesCurrentDocumentAndConfig() {
        KnowledgeDocument document = document("Use prepared statements.");
        KnowledgeChunk chunk = matchingChunk(document);
        when(chunkRepository.findByDocumentIdOrderByChunkIndexAsc("doc-1")).thenReturn(List.of(chunk));

        assertFalse(service.chunksStale(document));
    }

    @Test
    void chunksStaleShouldDetectSourceContentHashChanges() {
        KnowledgeDocument document = document("Use prepared statements.");
        KnowledgeChunk chunk = matchingChunk(document);
        document.setContent("Changed source content.");
        when(chunkRepository.findByDocumentIdOrderByChunkIndexAsc("doc-1")).thenReturn(List.of(chunk));

        assertTrue(service.chunksStale(document));
    }

    @Test
    void chunksStaleShouldDetectStrategyVersionChanges() {
        KnowledgeDocument document = document("Use prepared statements.");
        KnowledgeChunk chunk = matchingChunk(document);
        chunk.setChunkStrategy("old_strategy_v0");
        when(chunkRepository.findByDocumentIdOrderByChunkIndexAsc("doc-1")).thenReturn(List.of(chunk));

        assertTrue(service.chunksStale(document));
    }

    @Test
    void chunksStaleShouldDetectConfigHashChanges() {
        KnowledgeDocument document = document("Use prepared statements.");
        KnowledgeChunk chunk = matchingChunk(document);
        properties.setTargetTokens(properties.getTargetTokens() + 1);
        when(chunkRepository.findByDocumentIdOrderByChunkIndexAsc("doc-1")).thenReturn(List.of(chunk));

        assertTrue(service.chunksStale(document));
    }

    @Test
    void rebuildDocumentChunksShouldDeleteExistingChunksForDocument() {
        KnowledgeDocument document = document("Use prepared statements.");

        service.rebuildDocumentChunks(document);

        verify(chunkRepository).deleteByDocumentId("doc-1");
        verify(chunkRepository).saveAll(anyList());
    }

    @Test
    void rebuildDocumentChunksShouldPersistStructuredChunks() {
        KnowledgeDocument document = document("""
                # SQL Rules

                CG-CODE-SQL-001 Parameterized queries
                Use prepared statements for every SQL query.

                CG-CODE-SECRET-001 Secret storage
                Do not hardcode database credentials.
                """);
        doAnswer(invocation -> invocation.getArgument(0)).when(chunkRepository).saveAll(anyList());

        service.rebuildDocumentChunks(document);

        verify(chunkRepository).saveAll(argThat(chunks -> {
            List<KnowledgeChunk> saved = toList(chunks);
            if (saved.size() != 2) {
                return false;
            }

            KnowledgeChunk first = saved.get(0);
            return "doc-1".equals(first.getDocumentId())
                    && Integer.valueOf(0).equals(first.getChunkIndex())
                    && "SQL Rules".equals(first.getTitle())
                    && first.getContent().contains("Parameterized queries")
                    && "SQL Rules".equals(first.getHeadingPath())
                    && first.getRuleIds().contains("CG-CODE-SQL-001")
                    && properties.getStrategyVersion().equals(first.getChunkStrategy())
                    && properties.configHash().equals(first.getChunkConfigHash())
                    && sha256(document.getContent()).equals(first.getSourceContentHash())
                    && first.getCharStart() != null
                    && first.getCharEnd() != null
                    && first.getTokenCount() != null
                    && first.getMetadata().containsKey("split_reason");
        }));
    }

    @Test
    void rebuildDocumentChunksShouldKeepMatchingChunksFreshAfterRebuild() {
        KnowledgeDocument document = document("Use prepared statements.");
        doAnswer(invocation -> invocation.getArgument(0)).when(chunkRepository).saveAll(anyList());

        service.rebuildDocumentChunks(document);

        verify(chunkRepository).saveAll(argThat(chunks -> {
            KnowledgeChunk chunk = toList(chunks).get(0);
            assertNotNull(chunk.getId());
            assertEquals(sha256(document.getContent()), chunk.getSourceContentHash());
            assertEquals(properties.getStrategyVersion(), chunk.getChunkStrategy());
            assertEquals(properties.configHash(), chunk.getChunkConfigHash());
            return true;
        }));
    }

    private List<KnowledgeChunk> toList(Iterable<KnowledgeChunk> chunks) {
        List<KnowledgeChunk> result = new ArrayList<>();
        chunks.forEach(result::add);
        return result;
    }

    private KnowledgeDocument document(String content) {
        return KnowledgeDocument.builder()
                .id("doc-1")
                .title("SQL Rules")
                .content(content)
                .category("SECURITY")
                .build();
    }

    private KnowledgeChunk matchingChunk(KnowledgeDocument document) {
        return KnowledgeChunk.builder()
                .id("chunk-1")
                .documentId(document.getId())
                .chunkIndex(0)
                .content(document.getContent())
                .sourceContentHash(sha256(document.getContent()))
                .chunkStrategy(properties.getStrategyVersion())
                .chunkConfigHash(properties.configHash())
                .build();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((value != null ? value : "").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
