package com.codeguardian.service.provenance;

import com.codeguardian.dto.ReviewAssuranceSummaryDTO;
import com.codeguardian.dto.ReviewEvidenceDTO;
import com.codeguardian.dto.ReviewGroundingPolicyDTO;
import com.codeguardian.dto.ReviewIntegrityDTO;
import com.codeguardian.dto.ReviewProofBundleDTO;
import com.codeguardian.dto.ReviewProofBundleVerificationDTO;
import com.codeguardian.dto.ReviewRuntimeManifestDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReviewAssuranceServiceTest {

    @Test
    void should_build_proven_assurance_summary_when_all_checks_pass() {
        ReviewProofBundleService proofBundleService = mock(ReviewProofBundleService.class);
        ReviewAssuranceService service = new ReviewAssuranceService(proofBundleService);
        ReviewProofBundleDTO bundle = provenBundle();
        when(proofBundleService.buildBundle(7L)).thenReturn(bundle);
        when(proofBundleService.verifyBundleAgainstCurrentState(7L, bundle)).thenReturn(passingVerification(bundle));

        ReviewAssuranceSummaryDTO summary = service.buildSummary(7L);

        assertEquals(ReviewAssuranceService.SUMMARY_VERSION, summary.getSummaryVersion());
        assertEquals("PROVEN", summary.getVerdict());
        assertEquals(Boolean.TRUE, summary.getValid());
        assertEquals("ok", summary.getReason());
        assertEquals("state-hash", summary.getReviewStateHash());
        assertEquals("bundle-hash", summary.getBundleHash());
        assertEquals("manifest-hash", summary.getRuntimeManifestHash());
        assertEquals("kb-hash", summary.getKnowledgeBaseFingerprint());
        assertEquals(1, summary.getCounts().getHighRiskFindingCount());
        assertEquals(1, summary.getCounts().getGroundedHighRiskFindingCount());
        assertEquals(1, summary.getEvidenceTypeCounts().get("SOURCE_CODE"));
        assertEquals(1, summary.getEvidenceTypeCounts().get("RAG_SNIPPET"));
        assertTrue(summary.getChecks().stream().anyMatch(check -> "database_append_only_guard".equals(check.getId())
                && Boolean.TRUE.equals(check.getValid())));
        assertTrue(summary.getChecks().stream().allMatch(check -> Boolean.TRUE.equals(check.getValid())));
    }

    @Test
    void should_build_unproven_assurance_summary_when_current_state_does_not_match() {
        ReviewProofBundleService proofBundleService = mock(ReviewProofBundleService.class);
        ReviewAssuranceService service = new ReviewAssuranceService(proofBundleService);
        ReviewProofBundleDTO bundle = provenBundle();
        ReviewProofBundleVerificationDTO verification = passingVerification(bundle);
        verification.setValid(false);
        verification.setCurrentStateMatch(false);
        verification.setCurrentReviewStateHash("current-state-hash");
        verification.setReason("current_state_mismatch");
        when(proofBundleService.buildBundle(7L)).thenReturn(bundle);
        when(proofBundleService.verifyBundleAgainstCurrentState(7L, bundle)).thenReturn(verification);

        ReviewAssuranceSummaryDTO summary = service.buildSummary(7L);

        assertEquals("UNPROVEN", summary.getVerdict());
        assertEquals(Boolean.FALSE, summary.getValid());
        assertEquals("current_state_mismatch", summary.getReason());
        assertTrue(summary.getChecks().stream().anyMatch(check -> "current_state".equals(check.getId())
                && Boolean.FALSE.equals(check.getValid())
                && check.getReason().contains("current-state-hash")));
    }

    @Test
    void should_build_unproven_assurance_summary_when_database_append_only_guard_is_missing() {
        ReviewProofBundleService proofBundleService = mock(ReviewProofBundleService.class);
        ReviewAssuranceService service = new ReviewAssuranceService(proofBundleService);
        ReviewProofBundleDTO bundle = provenBundle();
        bundle.getRuntimeManifest().setDatabaseGuards(ReviewRuntimeManifestDTO.DatabaseGuardSnapshot.builder()
                .querySupported(true)
                .appendOnlyGuardsInstalled(false)
                .updatesBlocked(true)
                .deletesBlocked(true)
                .verificationReason("append_only_guard_missing")
                .build());
        when(proofBundleService.buildBundle(7L)).thenReturn(bundle);
        when(proofBundleService.verifyBundleAgainstCurrentState(7L, bundle)).thenReturn(passingVerification(bundle));

        ReviewAssuranceSummaryDTO summary = service.buildSummary(7L);

        assertEquals("UNPROVEN", summary.getVerdict());
        assertEquals(Boolean.FALSE, summary.getValid());
        assertEquals("database_append_only_guard", summary.getReason());
        assertTrue(summary.getChecks().stream().anyMatch(check -> "database_append_only_guard".equals(check.getId())
                && Boolean.FALSE.equals(check.getValid())
                && "append_only_guard_missing".equals(check.getReason())));
    }

    private ReviewProofBundleDTO provenBundle() {
        return ReviewProofBundleDTO.builder()
                .reviewStateHash("state-hash")
                .bundleHash("bundle-hash")
                .runtimeManifest(ReviewRuntimeManifestDTO.builder()
                        .manifestHash("manifest-hash")
                        .rag(ReviewRuntimeManifestDTO.RagSnapshot.builder()
                                .knowledgeBaseFingerprint("kb-hash")
                                .build())
                        .databaseGuards(ReviewRuntimeManifestDTO.DatabaseGuardSnapshot.builder()
                                .querySupported(true)
                                .appendOnlyGuardsInstalled(true)
                                .updatesBlocked(true)
                                .deletesBlocked(true)
                                .verificationReason("ok")
                                .build())
                        .build())
                .integrity(ReviewIntegrityDTO.builder()
                        .auditChainValid(true)
                        .auditSignatureValid(true)
                        .auditCoverageValid(true)
                        .auditOrderValid(true)
                        .auditOrderViolations(List.of())
                        .build())
                .groundingPolicy(ReviewGroundingPolicyDTO.builder()
                        .valid(true)
                        .reason("ok")
                        .violationCount(0)
                        .build())
                .counts(ReviewProofBundleDTO.Counts.builder()
                        .findingCount(1)
                        .groundedFindingCount(1L)
                        .evidenceCount(2)
                        .auditEventCount(3)
                        .signedAuditEventCount(3)
                        .groundingViolationCount(0)
                        .build())
                .findings(List.of(ReviewProofBundleDTO.FindingSnapshot.builder()
                        .id(3L)
                        .severity(0)
                        .grounded(true)
                        .build()))
                .evidence(List.of(
                        ReviewEvidenceDTO.builder().id(11L).evidenceType("SOURCE_CODE").build(),
                        ReviewEvidenceDTO.builder().id(12L).evidenceType("RAG_SNIPPET").build()
                ))
                .build();
    }

    private ReviewProofBundleVerificationDTO passingVerification(ReviewProofBundleDTO bundle) {
        return ReviewProofBundleVerificationDTO.builder()
                .valid(true)
                .schemaVersionValid(true)
                .evidenceHashValid(true)
                .reviewStateHashValid(true)
                .runtimeManifestHashValid(true)
                .groundingPolicyValid(true)
                .auditChainValid(true)
                .auditSignatureValid(true)
                .auditCoverageValid(true)
                .auditOrderValid(true)
                .bundleHashValid(true)
                .bundleSignatureValid(true)
                .currentStateMatch(true)
                .reason("ok")
                .schemaVersion(ReviewProofBundleService.SCHEMA_VERSION)
                .invalidEvidenceCount(0)
                .invalidEvidenceRefs(List.of())
                .groundingViolationCount(0)
                .expectedReviewStateHash(bundle.getReviewStateHash())
                .providedReviewStateHash(bundle.getReviewStateHash())
                .currentReviewStateHash(bundle.getReviewStateHash())
                .runtimeManifestHash(bundle.getRuntimeManifest().getManifestHash())
                .expectedBundleHash(bundle.getBundleHash())
                .providedBundleHash(bundle.getBundleHash())
                .bundleSignatureKeyId("unit-key")
                .build();
    }
}
