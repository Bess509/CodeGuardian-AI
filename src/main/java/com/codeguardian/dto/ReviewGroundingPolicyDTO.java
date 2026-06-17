package com.codeguardian.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewGroundingPolicyDTO {
    private String policyVersion;
    private String minSeverity;
    private List<String> requiredEvidenceTypes;
    private Boolean valid;
    private String reason;
    private Integer totalFindingCount;
    private Integer requiredFindingCount;
    private Integer passedFindingCount;
    private Integer violationCount;
    private Integer missingEvidenceCount;
    private Integer missingSourceEvidenceCount;
    private Integer evidenceCountMismatchCount;
    private Integer evidenceHashMismatchCount;
    private Integer invalidEvidenceContentHashCount;
    private Integer invalidSourceAnchorCount;
    private List<Violation> violations;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Violation {
        private Long findingId;
        private Integer severity;
        private String severityLabel;
        private String title;
        private String location;
        private List<String> reasons;
        private List<String> evidenceTypes;
        private Integer expectedEvidenceCount;
        private Integer providedEvidenceCount;
        private String expectedEvidenceHash;
        private String providedEvidenceHash;
        private List<Long> invalidSourceEvidenceIds;
    }
}
