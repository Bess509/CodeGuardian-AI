package com.codeguardian.service.provenance;

import com.codeguardian.dto.ReviewAssuranceSummaryDTO;
import com.codeguardian.dto.ReviewEvidenceDTO;
import com.codeguardian.dto.ReviewProofBundleDTO;
import com.codeguardian.dto.ReviewProofBundleVerificationDTO;
import com.codeguardian.dto.ReviewRuntimeManifestDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReviewAssuranceService {

    public static final String SUMMARY_VERSION = "codeguardian-review-assurance-summary-v1";

    private final ReviewProofBundleService proofBundleService;

    public ReviewAssuranceSummaryDTO buildSummary(Long taskId) {
        ReviewProofBundleDTO bundle = proofBundleService.buildBundle(taskId);
        ReviewProofBundleVerificationDTO verification = proofBundleService.verifyBundleAgainstCurrentState(taskId, bundle);
        List<ReviewAssuranceSummaryDTO.AssuranceCheck> checks = buildChecks(bundle, verification);
        boolean proven = checks.stream().allMatch(check -> Boolean.TRUE.equals(check.getValid()));

        return ReviewAssuranceSummaryDTO.builder()
                .summaryVersion(SUMMARY_VERSION)
                .taskId(taskId)
                .generatedAt(LocalDateTime.now())
                .verdict(proven ? "PROVEN" : "UNPROVEN")
                .valid(proven)
                .reason(proven ? "ok" : firstFailureReason(checks, verification))
                .reviewStateHash(bundle.getReviewStateHash())
                .bundleHash(bundle.getBundleHash())
                .runtimeManifestHash(bundle.getRuntimeManifest() != null ? bundle.getRuntimeManifest().getManifestHash() : null)
                .knowledgeBaseFingerprint(bundle.getRuntimeManifest() != null
                        && bundle.getRuntimeManifest().getRag() != null
                        ? bundle.getRuntimeManifest().getRag().getKnowledgeBaseFingerprint()
                        : null)
                .counts(buildCounts(bundle, verification))
                .checks(checks)
                .evidenceTypeCounts(evidenceTypeCounts(bundle))
                .build();
    }

    private List<ReviewAssuranceSummaryDTO.AssuranceCheck> buildChecks(ReviewProofBundleDTO bundle,
                                                                       ReviewProofBundleVerificationDTO verification) {
        List<ReviewAssuranceSummaryDTO.AssuranceCheck> checks = new ArrayList<>();
        checks.add(check("schema", "Proof bundle schema", verification.getSchemaVersionValid(),
                verification.getSchemaVersion(), "proof.schemaVersion"));
        checks.add(check("evidence_hash", "Evidence content hashes", verification.getEvidenceHashValid(),
                invalidEvidenceReason(verification), "proof.evidence[].contentHash"));
        checks.add(check("review_state_hash", "Review state hash", verification.getReviewStateHashValid(),
                hashReason(verification.getExpectedReviewStateHash(), verification.getProvidedReviewStateHash()),
                "proof.reviewStateHash"));
        checks.add(check("runtime_manifest_hash", "Runtime manifest hash", verification.getRuntimeManifestHashValid(),
                verification.getRuntimeManifestHash(), "proof.runtimeManifest.manifestHash"));
        checks.add(check("database_append_only_guard", "Database append-only guards",
                databaseGuardValid(bundle), databaseGuardReason(bundle),
                "proof.runtimeManifest.databaseGuards"));
        checks.add(check("grounding_policy", "Grounding policy", verification.getGroundingPolicyValid(),
                groundingReason(bundle, verification), "proof.groundingPolicy"));
        checks.add(check("audit_chain", "Audit chain", verification.getAuditChainValid(),
                integrityReason(bundle, "auditChainValid"), "proof.integrity.auditChainValid"));
        checks.add(check("audit_signature", "Audit signatures", verification.getAuditSignatureValid(),
                integrityReason(bundle, "auditSignatureValid"), "proof.integrity.auditSignatureValid"));
        checks.add(check("audit_coverage", "Audit coverage", verification.getAuditCoverageValid(),
                integrityReason(bundle, "auditCoverageValid"), "proof.integrity.auditCoverageValid"));
        checks.add(check("audit_order", "Audit event order", verification.getAuditOrderValid(),
                integrityReason(bundle, "auditOrderValid"), "proof.integrity.auditOrderValid"));
        checks.add(check("bundle_hash", "Proof bundle hash", verification.getBundleHashValid(),
                hashReason(verification.getExpectedBundleHash(), verification.getProvidedBundleHash()),
                "proof.bundleHash"));
        checks.add(check("bundle_signature", "Proof bundle signature", verification.getBundleSignatureValid(),
                verification.getBundleSignatureKeyId(), "proof.bundleSignature"));
        checks.add(check("current_state", "Current state match", verification.getCurrentStateMatch(),
                hashReason(verification.getCurrentReviewStateHash(), verification.getProvidedReviewStateHash()),
                "proof.reviewStateHash"));
        return checks;
    }

    private ReviewAssuranceSummaryDTO.AssuranceCounts buildCounts(ReviewProofBundleDTO bundle,
                                                                  ReviewProofBundleVerificationDTO verification) {
        int highRiskFindingCount = (int) safeFindings(bundle).stream()
                .filter(finding -> finding.getSeverity() != null && finding.getSeverity() <= 1)
                .count();
        int groundedHighRiskFindingCount = (int) safeFindings(bundle).stream()
                .filter(finding -> finding.getSeverity() != null && finding.getSeverity() <= 1)
                .filter(finding -> Boolean.TRUE.equals(finding.getGrounded()))
                .count();
        ReviewProofBundleDTO.Counts counts = bundle.getCounts();
        return ReviewAssuranceSummaryDTO.AssuranceCounts.builder()
                .findingCount(counts != null ? counts.getFindingCount() : safeFindings(bundle).size())
                .highRiskFindingCount(highRiskFindingCount)
                .groundedHighRiskFindingCount(groundedHighRiskFindingCount)
                .evidenceCount(counts != null ? counts.getEvidenceCount() : safeEvidence(bundle).size())
                .auditEventCount(counts != null ? counts.getAuditEventCount() : safeAuditCount(bundle))
                .signedAuditEventCount(counts != null ? counts.getSignedAuditEventCount() : null)
                .groundingViolationCount(counts != null ? counts.getGroundingViolationCount()
                        : verification.getGroundingViolationCount())
                .invalidEvidenceCount(verification.getInvalidEvidenceCount())
                .build();
    }

    private Map<String, Integer> evidenceTypeCounts(ReviewProofBundleDTO bundle) {
        return safeEvidence(bundle).stream()
                .filter(evidence -> evidence.getEvidenceType() != null && !evidence.getEvidenceType().isBlank())
                .collect(Collectors.toMap(
                        ReviewEvidenceDTO::getEvidenceType,
                        evidence -> 1,
                        Integer::sum,
                        LinkedHashMap::new
                ));
    }

    private ReviewAssuranceSummaryDTO.AssuranceCheck check(String id,
                                                           String label,
                                                           Boolean valid,
                                                           String reason,
                                                           String evidenceRef) {
        return ReviewAssuranceSummaryDTO.AssuranceCheck.builder()
                .id(id)
                .label(label)
                .valid(Boolean.TRUE.equals(valid))
                .reason(Boolean.TRUE.equals(valid) ? "ok" : nonBlank(reason, "failed"))
                .evidenceRef(evidenceRef)
                .build();
    }

    private String firstFailureReason(List<ReviewAssuranceSummaryDTO.AssuranceCheck> checks,
                                      ReviewProofBundleVerificationDTO verification) {
        if (verification != null && verification.getReason() != null && !"ok".equals(verification.getReason())) {
            return verification.getReason();
        }
        return checks.stream()
                .filter(check -> !Boolean.TRUE.equals(check.getValid()))
                .map(ReviewAssuranceSummaryDTO.AssuranceCheck::getId)
                .findFirst()
                .orElse("unproven");
    }

    private String invalidEvidenceReason(ReviewProofBundleVerificationDTO verification) {
        if (verification == null || verification.getInvalidEvidenceRefs() == null
                || verification.getInvalidEvidenceRefs().isEmpty()) {
            return null;
        }
        return String.join(",", verification.getInvalidEvidenceRefs());
    }

    private String groundingReason(ReviewProofBundleDTO bundle, ReviewProofBundleVerificationDTO verification) {
        if (verification != null && verification.getGroundingViolationCount() != null
                && verification.getGroundingViolationCount() > 0) {
            return "violations=" + verification.getGroundingViolationCount();
        }
        if (bundle != null && bundle.getGroundingPolicy() != null) {
            return bundle.getGroundingPolicy().getReason();
        }
        return null;
    }

    private Boolean databaseGuardValid(ReviewProofBundleDTO bundle) {
        ReviewRuntimeManifestDTO.DatabaseGuardSnapshot guard = databaseGuard(bundle);
        return guard != null
                && Boolean.TRUE.equals(guard.getQuerySupported())
                && Boolean.TRUE.equals(guard.getAppendOnlyGuardsInstalled())
                && Boolean.TRUE.equals(guard.getUpdatesBlocked())
                && Boolean.TRUE.equals(guard.getDeletesBlocked());
    }

    private String databaseGuardReason(ReviewProofBundleDTO bundle) {
        ReviewRuntimeManifestDTO.DatabaseGuardSnapshot guard = databaseGuard(bundle);
        if (guard == null) {
            return "database_guard_snapshot_missing";
        }
        if (!Boolean.TRUE.equals(guard.getQuerySupported())) {
            return nonBlank(guard.getVerificationReason(), "database_guard_query_unsupported");
        }
        if (!Boolean.TRUE.equals(guard.getAppendOnlyGuardsInstalled())) {
            return nonBlank(guard.getVerificationReason(), "append_only_guard_missing");
        }
        if (!Boolean.TRUE.equals(guard.getUpdatesBlocked()) || !Boolean.TRUE.equals(guard.getDeletesBlocked())) {
            return "append_only_guard_not_enforcing_mutation_block";
        }
        return "ok";
    }

    private ReviewRuntimeManifestDTO.DatabaseGuardSnapshot databaseGuard(ReviewProofBundleDTO bundle) {
        return bundle != null
                && bundle.getRuntimeManifest() != null
                ? bundle.getRuntimeManifest().getDatabaseGuards()
                : null;
    }

    private String integrityReason(ReviewProofBundleDTO bundle, String field) {
        if (bundle == null || bundle.getIntegrity() == null) {
            return "integrity_missing";
        }
        return field;
    }

    private String hashReason(String expected, String provided) {
        if (Objects.equals(expected, provided)) {
            return expected;
        }
        return "expected=" + nonBlank(expected, "null") + ";provided=" + nonBlank(provided, "null");
    }

    private String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private List<ReviewProofBundleDTO.FindingSnapshot> safeFindings(ReviewProofBundleDTO bundle) {
        return bundle != null && bundle.getFindings() != null ? bundle.getFindings() : List.of();
    }

    private List<ReviewEvidenceDTO> safeEvidence(ReviewProofBundleDTO bundle) {
        return bundle != null && bundle.getEvidence() != null ? bundle.getEvidence() : List.of();
    }

    private int safeAuditCount(ReviewProofBundleDTO bundle) {
        return bundle != null && bundle.getAuditEvents() != null ? bundle.getAuditEvents().size() : 0;
    }
}
