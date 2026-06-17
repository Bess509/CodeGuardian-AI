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
public class ReviewProofBundleVerificationDTO {
    private Boolean valid;
    private Boolean schemaVersionValid;
    private Boolean evidenceHashValid;
    private Boolean reviewStateHashValid;
    private Boolean runtimeManifestHashValid;
    private Boolean groundingPolicyValid;
    private Boolean reviewIntegrityValid;
    private Boolean auditChainValid;
    private Boolean auditSignatureValid;
    private Boolean auditCoverageValid;
    private Boolean auditOrderValid;
    private Boolean bundleHashValid;
    private Boolean bundleSignatureValid;
    private Boolean currentStateMatch;
    private Boolean currentReviewStateMatch;
    private Boolean currentBundleMatch;
    private Boolean currentRuntimeManifestMatch;
    private String reason;
    private String schemaVersion;
    private Integer evidenceCount;
    private Integer invalidEvidenceCount;
    private List<String> invalidEvidenceRefs;
    private Integer groundingViolationCount;
    private Long currentTaskId;
    private String expectedReviewStateHash;
    private String providedReviewStateHash;
    private String currentReviewStateHash;
    private String runtimeManifestHash;
    private String currentRuntimeManifestHash;
    private String expectedBundleHash;
    private String providedBundleHash;
    private String currentBundleHash;
    private String bundleSignatureKeyId;
    private String bundleSignatureAlgorithm;
}
