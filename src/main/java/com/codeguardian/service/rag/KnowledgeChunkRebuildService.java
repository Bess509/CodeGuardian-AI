package com.codeguardian.service.rag;

import com.codeguardian.entity.KnowledgeChunk;
import com.codeguardian.repository.KnowledgeChunkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class KnowledgeChunkRebuildService {

    private final KnowledgeChunkRepository chunkRepository;
    private final ChunkingProperties chunkingProperties;

    @Transactional(readOnly = true)
    public boolean chunksStale(KnowledgeDocument document) {
        if (document == null || !StringUtils.hasText(document.getId())) {
            return true;
        }

        List<KnowledgeChunk> chunks = chunkRepository.findByDocumentIdOrderByChunkIndexAsc(document.getId());
        if (chunks == null || chunks.isEmpty()) {
            return true;
        }

        String sourceHash = sourceContentHash(document);
        String strategy = chunkingProperties.getStrategyVersion();
        String configHash = chunkingProperties.configHash();
        return chunks.stream().anyMatch(chunk -> chunkStale(chunk, sourceHash, strategy, configHash));
    }

    @Transactional
    public void rebuildDocumentChunks(KnowledgeDocument document) {
        Assert.notNull(document, "document must not be null");
        Assert.hasText(document.getId(), "document id must not be blank");

        String sourceHash = sourceContentHash(document);
        String configHash = chunkingProperties.configHash();
        chunkRepository.deleteByDocumentId(document.getId());

        List<KnowledgeChunk> rebuiltChunks = buildChunks(document, sourceHash, configHash);
        if (!rebuiltChunks.isEmpty()) {
            chunkRepository.saveAll(rebuiltChunks);
        }
    }

    private boolean chunkStale(KnowledgeChunk chunk, String sourceHash, String strategy, String configHash) {
        return chunk == null
                || !Objects.equals(sourceHash, chunk.getSourceContentHash())
                || !Objects.equals(strategy, chunk.getChunkStrategy())
                || !Objects.equals(configHash, chunk.getChunkConfigHash());
    }

    private List<KnowledgeChunk> buildChunks(KnowledgeDocument document, String sourceHash, String configHash) {
        return List.of();
    }

    private String sourceContentHash(KnowledgeDocument document) {
        return sha256(document.getContent() != null ? document.getContent() : "");
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
