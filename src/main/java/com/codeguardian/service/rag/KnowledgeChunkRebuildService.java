package com.codeguardian.service.rag;

import com.codeguardian.entity.KnowledgeChunk;
import com.codeguardian.repository.KnowledgeChunkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    @Transactional
    public void deleteDocumentChunks(String documentId) {
        Assert.hasText(documentId, "document id must not be blank");
        chunkRepository.deleteByDocumentId(documentId);
    }

    private boolean chunkStale(KnowledgeChunk chunk, String sourceHash, String strategy, String configHash) {
        return chunk == null
                || !Objects.equals(sourceHash, chunk.getSourceContentHash())
                || !Objects.equals(strategy, chunk.getChunkStrategy())
                || !Objects.equals(configHash, chunk.getChunkConfigHash());
    }

    private List<KnowledgeChunk> buildChunks(KnowledgeDocument document, String sourceHash, String configHash) {
        String content = document.getContent() != null ? document.getContent() : "";
        Map<String, Object> baseMetadata = document.getMetadata() != null
                ? new LinkedHashMap<>(document.getMetadata())
                : new LinkedHashMap<>();
        baseMetadata.put("title", document.getTitle());
        baseMetadata.put("category", document.getCategory());

        List<Document> splitChunks = StructuredDocumentChunker.split(document.getId(), content, baseMetadata);
        List<KnowledgeChunk> chunks = new ArrayList<>();
        for (int i = 0; i < splitChunks.size(); i++) {
            Document splitChunk = splitChunks.get(i);
            Map<String, Object> metadata = splitChunk.getMetadata() != null
                    ? new LinkedHashMap<>(splitChunk.getMetadata())
                    : new LinkedHashMap<>();
            metadata.put("chunk_strategy", chunkingProperties.getStrategyVersion());
            metadata.put("chunk_config_hash", configHash);
            metadata.put("source_content_hash", sourceHash);

            int chunkIndex = intValue(metadata.get("chunk_index"), i);
            String chunkId = firstNonBlank(stringValue(metadata.get("chunk_id")), splitChunk.getId());
            chunks.add(KnowledgeChunk.builder()
                    .id(chunkId)
                    .documentId(document.getId())
                    .chunkIndex(chunkIndex)
                    .title(document.getTitle())
                    .content(splitChunk.getContent())
                    .headingPath(stringValue(metadata.get("heading_path")))
                    .ruleIds(stringList(metadata.get("rule_ids")))
                    .chunkStrategy(chunkingProperties.getStrategyVersion())
                    .chunkConfigHash(configHash)
                    .sourceContentHash(sourceHash)
                    .charStart(intValue(metadata.get("char_start"), null))
                    .charEnd(intValue(metadata.get("char_end"), null))
                    .tokenCount(intValue(metadata.get("token_count"), null))
                    .overlapTokens(intValue(metadata.get("overlap_tokens"), chunkingProperties.getOverlapTokens()))
                    .metadata(metadata)
                    .build());
        }
        return chunks;
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

    private Integer intValue(Object value, Integer fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private String stringValue(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private String firstNonBlank(String first, String second) {
        if (StringUtils.hasText(first)) {
            return first;
        }
        return second;
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null && StringUtils.hasText(String.valueOf(item))) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        return new ArrayList<>();
    }
}
