package com.codeguardian.entity;

import com.codeguardian.util.MapJsonConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Evidence used to ground a review task or a specific finding.
 */
@Entity
@Table(name = "review_evidence")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false, updatable = false)
    private Long taskId;

    @Column(name = "finding_id", updatable = false)
    private Long findingId;

    @Column(name = "evidence_type", nullable = false, length = 48, updatable = false)
    private String evidenceType;

    @Column(name = "source_name", length = 128, updatable = false)
    private String sourceName;

    @Column(name = "source_ref", columnDefinition = "TEXT", updatable = false)
    private String sourceRef;

    @Column(name = "locator", length = 255, updatable = false)
    private String locator;

    @Column(name = "start_line", updatable = false)
    private Integer startLine;

    @Column(name = "end_line", updatable = false)
    private Integer endLine;

    @Column(name = "excerpt", columnDefinition = "TEXT", updatable = false)
    private String excerpt;

    @Column(name = "content_hash", length = 64, updatable = false)
    private String contentHash;

    @Column(name = "relevance_score", updatable = false)
    private Double relevanceScore;

    @Column(name = "metadata", columnDefinition = "TEXT", updatable = false)
    @Convert(converter = MapJsonConverter.class)
    private Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    public void preventUpdate() {
        throw new UnsupportedOperationException("Review evidence records are append-only and cannot be updated");
    }
}
