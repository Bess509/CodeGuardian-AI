package com.codeguardian.service.integration;

import com.codeguardian.dto.ReviewProofBundleDTO;
import com.codeguardian.dto.ReviewProofBundleVerificationDTO;
import com.codeguardian.dto.ReviewRuntimeManifestDTO;
import com.codeguardian.service.provenance.ReviewProofBundleService;
import lombok.Builder;
import lombok.Data;

final class QualityGateProofAssuranceEvaluator {

    private final ReviewProofBundleService proofBundleService;

    QualityGateProofAssuranceEvaluator(ReviewProofBundleService proofBundleService) {
        this.proofBundleService = proofBundleService;
    }

    ProofAssurance evaluate(Long taskId) {
        if (taskId == null || proofBundleService == null) {
            return null;
        }
        try {
            ReviewProofBundleDTO bundle = proofBundleService.buildBundle(taskId);
            ReviewProofBundleVerificationDTO verification = proofBundleService.verifyBundleAgainstCurrentState(taskId, bundle);
            Boolean auditChainValid = bundle.getIntegrity() != null ? bundle.getIntegrity().getAuditChainValid() : false;
            Boolean auditSignatureValid = bundle.getIntegrity() != null ? bundle.getIntegrity().getAuditSignatureValid() : false;
            Boolean auditCoverageValid = bundle.getIntegrity() != null ? bundle.getIntegrity().getAuditCoverageValid() : false;
            Boolean auditOrderValid = bundle.getIntegrity() != null ? bundle.getIntegrity().getAuditOrderValid() : false;
            RuntimeGuardAssurance runtimeGuard = evaluateRuntimeGuard(bundle);
            boolean valid = Boolean.TRUE.equals(verification.getValid())
                    && Boolean.TRUE.equals(auditChainValid)
                    && Boolean.TRUE.equals(auditSignatureValid)
                    && Boolean.TRUE.equals(auditCoverageValid)
                    && Boolean.TRUE.equals(auditOrderValid)
                    && Boolean.TRUE.equals(runtimeGuard.getValid());
            return ProofAssurance.builder()
                    .valid(valid)
                    .proofBundleValid(verification.getValid())
                    .proofBundleReason(verification.getReason())
                    .auditChainValid(auditChainValid)
                    .auditSignatureValid(auditSignatureValid)
                    .auditCoverageValid(auditCoverageValid)
                    .auditOrderValid(auditOrderValid)
                    .evidenceHashValid(verification.getEvidenceHashValid())
                    .reviewStateHashValid(verification.getReviewStateHashValid())
                    .runtimeManifestHashValid(verification.getRuntimeManifestHashValid())
                    .bundleHashValid(verification.getBundleHashValid())
                    .bundleSignatureValid(verification.getBundleSignatureValid())
                    .currentStateMatch(verification.getCurrentStateMatch())
                    .currentReviewStateMatch(verification.getCurrentReviewStateMatch())
                    .currentRuntimeManifestMatch(verification.getCurrentRuntimeManifestMatch())
                    .currentBundleMatch(verification.getCurrentBundleMatch())
                    .runtimeGuardValid(runtimeGuard.getValid())
                    .dbGuardQuerySupported(runtimeGuard.getQuerySupported())
                    .dbAppendOnlyGuardsInstalled(runtimeGuard.getAppendOnlyGuardsInstalled())
                    .dbGuardUpdatesBlocked(runtimeGuard.getUpdatesBlocked())
                    .dbGuardDeletesBlocked(runtimeGuard.getDeletesBlocked())
                    .dbGuardReason(runtimeGuard.getReason())
                    .build();
        } catch (Exception e) {
            return ProofAssurance.builder()
                    .valid(false)
                    .proofBundleValid(false)
                    .proofBundleReason("proof_bundle_unavailable")
                    .auditChainValid(false)
                    .auditSignatureValid(false)
                    .auditCoverageValid(false)
                    .auditOrderValid(false)
                    .runtimeGuardValid(false)
                    .dbGuardReason("proof_bundle_unavailable")
                    .build();
        }
    }

    private RuntimeGuardAssurance evaluateRuntimeGuard(ReviewProofBundleDTO bundle) {
        if (bundle == null || bundle.getRuntimeManifest() == null) {
            return RuntimeGuardAssurance.builder()
                    .valid(false)
                    .reason("runtime_manifest_missing")
                    .build();
        }
        ReviewRuntimeManifestDTO.DatabaseGuardSnapshot guard = bundle.getRuntimeManifest().getDatabaseGuards();
        if (guard == null) {
            return RuntimeGuardAssurance.builder()
                    .valid(false)
                    .reason("database_guard_snapshot_missing")
                    .build();
        }
        boolean querySupported = Boolean.TRUE.equals(guard.getQuerySupported());
        boolean appendOnlyInstalled = Boolean.TRUE.equals(guard.getAppendOnlyGuardsInstalled());
        boolean updatesBlocked = Boolean.TRUE.equals(guard.getUpdatesBlocked());
        boolean deletesBlocked = Boolean.TRUE.equals(guard.getDeletesBlocked());
        boolean valid = querySupported && appendOnlyInstalled && updatesBlocked && deletesBlocked;
        String reason = "ok";
        if (!querySupported) {
            reason = nonBlank(guard.getVerificationReason(), "database_guard_query_unsupported");
        } else if (!appendOnlyInstalled) {
            reason = nonBlank(guard.getVerificationReason(), "append_only_guard_missing");
        } else if (!updatesBlocked || !deletesBlocked) {
            reason = "append_only_guard_not_enforcing_mutation_block";
        }
        return RuntimeGuardAssurance.builder()
                .valid(valid)
                .querySupported(guard.getQuerySupported())
                .appendOnlyGuardsInstalled(guard.getAppendOnlyGuardsInstalled())
                .updatesBlocked(guard.getUpdatesBlocked())
                .deletesBlocked(guard.getDeletesBlocked())
                .reason(reason)
                .build();
    }

    private String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    @Data
    @Builder
    static class ProofAssurance {
        private boolean valid;
        private Boolean proofBundleValid;
        private String proofBundleReason;
        private Boolean auditChainValid;
        private Boolean auditSignatureValid;
        private Boolean auditCoverageValid;
        private Boolean auditOrderValid;
        private Boolean evidenceHashValid;
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

    @Data
    @Builder
    private static class RuntimeGuardAssurance {
        private Boolean valid;
        private Boolean querySupported;
        private Boolean appendOnlyGuardsInstalled;
        private Boolean updatesBlocked;
        private Boolean deletesBlocked;
        private String reason;
    }
}
