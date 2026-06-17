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
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Tamper-evident audit event for a review task.
 */
@Entity
@Table(name = "review_audit_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false, updatable = false)
    private Long taskId;

    @Column(name = "event_type", nullable = false, length = 64, updatable = false)
    private String eventType;

    @Column(name = "stage", length = 64, updatable = false)
    private String stage;

    @Column(name = "actor", length = 128, updatable = false)
    private String actor;

    @Column(name = "message", columnDefinition = "TEXT", updatable = false)
    private String message;

    @Column(name = "payload_hash", length = 64, updatable = false)
    private String payloadHash;

    @Column(name = "previous_hash", length = 64, updatable = false)
    private String previousHash;

    @Column(name = "event_hash", nullable = false, length = 64, updatable = false)
    private String eventHash;

    @Column(name = "signature_key_id", length = 128, updatable = false)
    private String signatureKeyId;

    @Column(name = "signature_algorithm", length = 32, updatable = false)
    private String signatureAlgorithm;

    @Column(name = "event_signature", length = 128, updatable = false)
    private String eventSignature;

    @Column(name = "metadata", columnDefinition = "TEXT", updatable = false)
    @Convert(converter = MapJsonConverter.class)
    private Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        } else {
            createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        }
    }

    @PreUpdate
    public void preventUpdate() {
        throw new UnsupportedOperationException("Review audit events are append-only and cannot be updated");
    }
}
