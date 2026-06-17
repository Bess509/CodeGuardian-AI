package com.codeguardian.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewAssuranceSummaryDTO {
    private String summaryVersion;
    private Long taskId;
    private LocalDateTime generatedAt;
    private String verdict;
    private Boolean valid;
    private String reason;
    private String reviewStateHash;
    private String bundleHash;
    private String runtimeManifestHash;
    private String knowledgeBaseFingerprint;
    private AssuranceCounts counts;
    private List<AssuranceCheck> checks;
    private Map<String, Integer> evidenceTypeCounts;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssuranceCounts {
        private Integer findingCount;
        private Integer highRiskFindingCount;
        private Integer groundedHighRiskFindingCount;
        private Integer evidenceCount;
        private Integer auditEventCount;
        private Integer signedAuditEventCount;
        private Integer groundingViolationCount;
        private Integer invalidEvidenceCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssuranceCheck {
        private String id;
        private String label;
        private Boolean valid;
        private String reason;
        private String evidenceRef;
    }
}
