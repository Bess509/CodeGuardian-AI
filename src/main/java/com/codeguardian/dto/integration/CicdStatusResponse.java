package com.codeguardian.dto.integration;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CicdStatusResponse {
    private Long taskId;
    private String status; // RUNNING, COMPLETED, FAILED
    private boolean passed; // 是否通过门禁
    private String message;
    private String reportUrl;
    private Summary summary;
    private QualityGate qualityGate;

    @Data
    @Builder
    public static class Summary {
        private int critical;
        private int high;
        private int medium;
        private int low;
    }

    @Data
    @Builder
    public static class QualityGate {
        private String reason;
        private boolean severityBlocked;
        private boolean provenanceBlocked;
        private boolean integrityBlocked;
        private long ungroundedCritical;
        private long ungroundedHigh;
        private long ungroundedRisk;
        private String groundingMinSeverity;
        private Boolean groundingPolicyValid;
        private String groundingPolicyReason;
        private int groundingViolationCount;
        private int missingSourceEvidenceCount;
        private int evidenceCountMismatchCount;
        private int evidenceHashMismatchCount;
        private int invalidEvidenceContentHashCount;
        private int invalidSourceAnchorCount;
        private Boolean proofBundleValid;
        private String proofBundleReason;
        private Boolean auditChainValid;
        private Boolean auditSignatureValid;
        private Boolean auditCoverageValid;
        private Boolean auditOrderValid;
        private Boolean evidenceHashesValid;
        private Boolean reviewStateHashValid;
        private Boolean runtimeManifestHashValid;
        private Boolean bundleHashValid;
        private Boolean bundleSignatureValid;
        private Boolean currentStateMatch;
        private Boolean currentReviewStateMatch;
        private Boolean currentRuntimeManifestMatch;
        private Boolean currentBundleMatch;
        private Boolean runtimeGuardValid;
        private Boolean dbGuardQuerySupported;
        private Boolean dbAppendOnlyGuardsInstalled;
        private Boolean dbGuardUpdatesBlocked;
        private Boolean dbGuardDeletesBlocked;
        private String dbGuardReason;
    }
}
