package com.codeguardian.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Entity
@Table(
        name = "knowledge_chunk",
        indexes = {
                @Index(name = "idx_knowledge_chunk_document_id", columnList = "document_id"),
                @Index(name = "idx_knowledge_chunk_strategy", columnList = "chunk_strategy"),
                @Index(name = "idx_knowledge_chunk_source_hash", columnList = "source_content_hash")
        },
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_knowledge_chunk_document_index",
                        columnNames = {"document_id", "chunk_index"}
                )
        }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeChunk {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "document_id", nullable = false, length = 64)
    private String documentId;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(name = "title")
    private String title;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "heading_path", columnDefinition = "TEXT")
    private String headingPath;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rule_ids", columnDefinition = "jsonb")
    private List<String> ruleIds = new ArrayList<>();

    @Column(name = "chunk_strategy", length = 96)
    private String chunkStrategy;

    @Column(name = "chunk_config_hash", length = 64)
    private String chunkConfigHash;

    @Column(name = "source_content_hash", length = 64)
    private String sourceContentHash;

    @Column(name = "char_start")
    private Integer charStart;

    @Column(name = "char_end")
    private Integer charEnd;

    @Column(name = "token_count")
    private Integer tokenCount;

    @Column(name = "overlap_tokens")
    private Integer overlapTokens;

    @Builder.Default
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        normalizeCollections();
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
        normalizeCollections();
    }

    private void normalizeCollections() {
        if (ruleIds == null) {
            ruleIds = new ArrayList<>();
        }
        if (metadata == null) {
            metadata = new LinkedHashMap<>();
        }
    }
}
