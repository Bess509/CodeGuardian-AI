package com.codeguardian.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewProofBundleDTO {
    private String schemaVersion;
    private LocalDateTime generatedAt;
    private String reviewStateHashAlgorithm;
    private String reviewStateHash;
    private String bundleHashAlgorithm;
    private String bundleHash;
    private String bundleSignatureKeyId;
    private String bundleSignatureAlgorithm;
    private String bundleSignature;
    private TaskSnapshot task;
    private ReviewIntegrityDTO integrity;
    private ReviewRuntimeManifestDTO runtimeManifest;
    private ReviewGroundingPolicyDTO groundingPolicy;
    private Counts counts;
    private List<ReviewAuditEventDTO> auditEvents;
    private List<ReviewEvidenceDTO> evidence;
    private List<FindingSnapshot> findings;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskSnapshot {
        private Long id;
        private String name;
        private Integer reviewType;
        private String reviewTypeLabel;
        private String scope;
        private String scopeHash;
        private Integer status;
        private String statusLabel;
        private LocalDateTime createdAt;
        private LocalDateTime completedAt;
        private String errorMessage;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Counts {
        private Integer auditEventCount;
        private Integer evidenceCount;
        private Integer findingCount;
        private Long groundedFindingCount;
        private Integer signedAuditEventCount;
        private Integer groundingViolationCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FindingSnapshot {
        private Long id;
        private Long taskId;
        private Integer severity;
        private String severityLabel;
        private String title;
        private String location;
        private Integer startLine;
        private Integer endLine;
        private String description;
        private String suggestion;
        private String diff;
        private String category;
        private String source;
        private Double confidence;
        private Boolean grounded;
        private Integer evidenceCount;
        private String evidenceHash;
        private String groundingSummary;
        private LocalDateTime createdAt;
    }
}
