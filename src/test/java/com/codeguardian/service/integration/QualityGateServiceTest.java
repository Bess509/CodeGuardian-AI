package com.codeguardian.service.integration;

import com.codeguardian.dto.ReviewGroundingPolicyDTO;
import com.codeguardian.dto.ReviewIntegrityDTO;
import com.codeguardian.dto.ReviewProofBundleDTO;
import com.codeguardian.dto.ReviewProofBundleVerificationDTO;
import com.codeguardian.dto.ReviewRuntimeManifestDTO;
import com.codeguardian.entity.Finding;
import com.codeguardian.repository.FindingRepository;
import com.codeguardian.service.provenance.ReviewGroundingPolicyService;
import com.codeguardian.service.provenance.ReviewProofBundleService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QualityGateServiceTest {

    private final QualityGateService service = new QualityGateService(null);

    @Test
    void should_pass_when_no_findings() {
        assertTrue(service.checkQualityGate(List.of(), "CRITICAL"));
    }

    @Test
    void should_block_when_critical_found() {
        List<Finding> findings = List.of(
                groundedFinding(com.codeguardian.enums.SeverityEnum.CRITICAL.getValue())
        );
        assertFalse(service.checkQualityGate(findings, "CRITICAL"));
    }

    @Test
    void should_pass_when_critical_found_but_blocking_on_none() {
        List<Finding> findings = List.of(
                groundedFinding(com.codeguardian.enums.SeverityEnum.CRITICAL.getValue())
        );
        // If blockOn is null or unknown, it passes (based on implementation logic)
        assertTrue(service.checkQualityGate(findings, null)); 
    }

    @Test
    void should_block_when_medium_found_and_blocking_on_medium() {
        List<Finding> findings = List.of(
                groundedFinding(com.codeguardian.enums.SeverityEnum.MEDIUM.getValue())
        );
        assertFalse(service.checkQualityGate(findings, "MEDIUM"));
    }
    
    @Test
    void should_block_when_high_found_and_blocking_on_low() {
        // If blocking on LOW, any LOW, MEDIUM, HIGH, CRITICAL should block
        List<Finding> findings = List.of(
                groundedFinding(com.codeguardian.enums.SeverityEnum.HIGH.getValue())
        );
        assertFalse(service.checkQualityGate(findings, "LOW"));
    }

    @Test
    void should_block_when_high_risk_finding_is_not_grounded_even_below_severity_threshold() {
        List<Finding> findings = List.of(
                Finding.builder()
                        .severity(com.codeguardian.enums.SeverityEnum.HIGH.getValue())
                        .grounded(false)
                        .evidenceCount(0)
                        .build()
        );

        QualityGateService.QualityGateResult result = service.evaluateQualityGate(findings, "CRITICAL");

        assertFalse(result.isPassed());
        assertFalse(result.isSeverityBlocked());
        assertTrue(result.isProvenanceBlocked());
        assertEquals("provenance_blocked", result.getReason());
        assertEquals(1, result.getUngroundedHighCount());
    }

    @Test
    void should_pass_when_medium_finding_is_not_grounded_and_threshold_is_critical() {
        List<Finding> findings = List.of(
                Finding.builder()
                        .severity(com.codeguardian.enums.SeverityEnum.MEDIUM.getValue())
                        .grounded(false)
                        .evidenceCount(0)
                        .build()
        );

        QualityGateService.QualityGateResult result = service.evaluateQualityGate(findings, "CRITICAL");

        assertTrue(result.isPassed());
        assertFalse(result.isSeverityBlocked());
        assertFalse(result.isProvenanceBlocked());
    }

    @Test
    void should_report_both_reasons_when_severity_and_provenance_fail() {
        List<Finding> findings = List.of(
                Finding.builder()
                        .severity(com.codeguardian.enums.SeverityEnum.CRITICAL.getValue())
                        .grounded(false)
                        .evidenceCount(0)
                        .build()
        );

        QualityGateService.QualityGateResult result = service.evaluateQualityGate(findings, "CRITICAL");

        assertFalse(result.isPassed());
        assertTrue(result.isSeverityBlocked());
        assertTrue(result.isProvenanceBlocked());
        assertEquals("severity_and_provenance_blocked", result.getReason());
        assertEquals(1, result.getUngroundedCriticalCount());
    }

    @Test
    void should_block_when_grounding_policy_fails_for_task() {
        FindingRepository findingRepository = mock(FindingRepository.class);
        ReviewGroundingPolicyService groundingPolicyService = mock(ReviewGroundingPolicyService.class);
        QualityGateService taskService = new QualityGateService(findingRepository, groundingPolicyService);
        Finding finding = groundedFinding(com.codeguardian.enums.SeverityEnum.HIGH.getValue());
        finding.setId(20L);

        when(findingRepository.findByTaskId(99L)).thenReturn(List.of(finding));
        when(groundingPolicyService.evaluateTaskPolicy(eq(99L), anyList()))
                .thenReturn(ReviewGroundingPolicyDTO.builder()
                        .valid(false)
                        .reason("grounding_policy_failed")
                        .violationCount(1)
                        .missingSourceEvidenceCount(1)
                        .evidenceCountMismatchCount(0)
                        .evidenceHashMismatchCount(0)
                        .invalidEvidenceContentHashCount(0)
                        .build());

        QualityGateService.QualityGateResult result = taskService.evaluateQualityGate(99L, "CRITICAL");

        assertFalse(result.isPassed());
        assertFalse(result.isSeverityBlocked());
        assertTrue(result.isProvenanceBlocked());
        assertEquals("provenance_blocked", result.getReason());
        assertEquals(Boolean.FALSE, result.getGroundingPolicyValid());
        assertEquals(1, result.getGroundingViolationCount());
        assertEquals(1, result.getMissingSourceEvidenceCount());
    }

    @Test
    void should_block_when_proof_bundle_or_audit_chain_is_invalid_for_task() {
        FindingRepository findingRepository = mock(FindingRepository.class);
        ReviewGroundingPolicyService groundingPolicyService = mock(ReviewGroundingPolicyService.class);
        ReviewProofBundleService proofBundleService = mock(ReviewProofBundleService.class);
        QualityGateService taskService = new QualityGateService(
                findingRepository,
                groundingPolicyService,
                proofBundleService
        );
        Finding finding = groundedFinding(com.codeguardian.enums.SeverityEnum.HIGH.getValue());

        ReviewProofBundleDTO bundle = ReviewProofBundleDTO.builder()
                .integrity(ReviewIntegrityDTO.builder()
                        .auditChainValid(false)
                        .auditSignatureValid(true)
                        .auditCoverageValid(true)
                        .auditOrderValid(true)
                        .build())
                .runtimeManifest(installedRuntimeManifest())
                .build();
        ReviewProofBundleVerificationDTO verification = ReviewProofBundleVerificationDTO.builder()
                .valid(true)
                .reason("ok")
                .evidenceHashValid(true)
                .reviewStateHashValid(true)
                .runtimeManifestHashValid(true)
                .bundleHashValid(true)
                .bundleSignatureValid(true)
                .currentStateMatch(true)
                .currentReviewStateMatch(true)
                .currentRuntimeManifestMatch(true)
                .currentBundleMatch(true)
                .build();

        when(findingRepository.findByTaskId(100L)).thenReturn(List.of(finding));
        when(groundingPolicyService.evaluateTaskPolicy(eq(100L), anyList()))
                .thenReturn(ReviewGroundingPolicyDTO.builder()
                        .valid(true)
                        .reason("ok")
                        .violationCount(0)
                        .build());
        when(proofBundleService.buildBundle(100L)).thenReturn(bundle);
        when(proofBundleService.verifyBundleAgainstCurrentState(100L, bundle)).thenReturn(verification);

        QualityGateService.QualityGateResult result = taskService.evaluateQualityGate(100L, "CRITICAL");

        assertFalse(result.isPassed());
        assertFalse(result.isSeverityBlocked());
        assertFalse(result.isProvenanceBlocked());
        assertTrue(result.isIntegrityBlocked());
        assertEquals("integrity_blocked", result.getReason());
        assertEquals(Boolean.TRUE, result.getProofBundleValid());
        assertEquals(Boolean.FALSE, result.getAuditChainValid());
        assertEquals(Boolean.TRUE, result.getAuditSignatureValid());
        assertEquals(Boolean.TRUE, result.getAuditOrderValid());
        assertEquals(Boolean.TRUE, result.getRuntimeGuardValid());
    }

    @Test
    void should_block_when_proof_bundle_verification_fails_for_task() {
        FindingRepository findingRepository = mock(FindingRepository.class);
        ReviewGroundingPolicyService groundingPolicyService = mock(ReviewGroundingPolicyService.class);
        ReviewProofBundleService proofBundleService = mock(ReviewProofBundleService.class);
        QualityGateService taskService = new QualityGateService(
                findingRepository,
                groundingPolicyService,
                proofBundleService
        );

        ReviewProofBundleDTO bundle = ReviewProofBundleDTO.builder()
                .integrity(ReviewIntegrityDTO.builder()
                        .auditChainValid(true)
                        .auditSignatureValid(true)
                        .auditCoverageValid(true)
                        .auditOrderValid(true)
                        .build())
                .runtimeManifest(installedRuntimeManifest())
                .build();
        ReviewProofBundleVerificationDTO verification = ReviewProofBundleVerificationDTO.builder()
                .valid(false)
                .reason("bundle_hash_mismatch")
                .evidenceHashValid(true)
                .reviewStateHashValid(true)
                .runtimeManifestHashValid(true)
                .bundleHashValid(false)
                .bundleSignatureValid(true)
                .currentStateMatch(true)
                .currentReviewStateMatch(true)
                .currentRuntimeManifestMatch(true)
                .currentBundleMatch(true)
                .build();

        when(findingRepository.findByTaskId(101L)).thenReturn(List.of());
        when(groundingPolicyService.evaluateTaskPolicy(eq(101L), anyList()))
                .thenReturn(ReviewGroundingPolicyDTO.builder()
                        .valid(true)
                        .reason("ok")
                        .violationCount(0)
                        .build());
        when(proofBundleService.buildBundle(101L)).thenReturn(bundle);
        when(proofBundleService.verifyBundleAgainstCurrentState(101L, bundle)).thenReturn(verification);

        QualityGateService.QualityGateResult result = taskService.evaluateQualityGate(101L, "CRITICAL");

        assertFalse(result.isPassed());
        assertFalse(result.isSeverityBlocked());
        assertFalse(result.isProvenanceBlocked());
        assertTrue(result.isIntegrityBlocked());
        assertEquals("integrity_blocked", result.getReason());
        assertEquals(Boolean.FALSE, result.getProofBundleValid());
        assertEquals("bundle_hash_mismatch", result.getProofBundleReason());
        assertEquals(Boolean.FALSE, result.getBundleHashValid());
        assertEquals(Boolean.TRUE, result.getRuntimeGuardValid());
    }

    @Test
    void should_block_when_audit_coverage_is_incomplete_for_task() {
        FindingRepository findingRepository = mock(FindingRepository.class);
        ReviewGroundingPolicyService groundingPolicyService = mock(ReviewGroundingPolicyService.class);
        ReviewProofBundleService proofBundleService = mock(ReviewProofBundleService.class);
        QualityGateService taskService = new QualityGateService(
                findingRepository,
                groundingPolicyService,
                proofBundleService
        );

        ReviewProofBundleDTO bundle = ReviewProofBundleDTO.builder()
                .integrity(ReviewIntegrityDTO.builder()
                        .auditChainValid(true)
                        .auditSignatureValid(true)
                        .auditCoverageValid(false)
                        .auditOrderValid(true)
                        .build())
                .runtimeManifest(installedRuntimeManifest())
                .build();
        ReviewProofBundleVerificationDTO verification = ReviewProofBundleVerificationDTO.builder()
                .valid(false)
                .reason("review_integrity_failed")
                .evidenceHashValid(true)
                .reviewStateHashValid(true)
                .runtimeManifestHashValid(true)
                .reviewIntegrityValid(false)
                .auditCoverageValid(false)
                .auditOrderValid(true)
                .bundleHashValid(true)
                .bundleSignatureValid(true)
                .currentStateMatch(true)
                .currentReviewStateMatch(true)
                .currentRuntimeManifestMatch(true)
                .currentBundleMatch(true)
                .build();

        when(findingRepository.findByTaskId(102L)).thenReturn(List.of());
        when(groundingPolicyService.evaluateTaskPolicy(eq(102L), anyList()))
                .thenReturn(ReviewGroundingPolicyDTO.builder()
                        .valid(true)
                        .reason("ok")
                        .violationCount(0)
                        .build());
        when(proofBundleService.buildBundle(102L)).thenReturn(bundle);
        when(proofBundleService.verifyBundleAgainstCurrentState(102L, bundle)).thenReturn(verification);

        QualityGateService.QualityGateResult result = taskService.evaluateQualityGate(102L, "CRITICAL");

        assertFalse(result.isPassed());
        assertTrue(result.isIntegrityBlocked());
        assertEquals("integrity_blocked", result.getReason());
        assertEquals(Boolean.FALSE, result.getAuditCoverageValid());
        assertEquals("review_integrity_failed", result.getProofBundleReason());
    }

    @Test
    void should_block_when_audit_event_order_is_invalid_for_task() {
        FindingRepository findingRepository = mock(FindingRepository.class);
        ReviewGroundingPolicyService groundingPolicyService = mock(ReviewGroundingPolicyService.class);
        ReviewProofBundleService proofBundleService = mock(ReviewProofBundleService.class);
        QualityGateService taskService = new QualityGateService(
                findingRepository,
                groundingPolicyService,
                proofBundleService
        );

        ReviewProofBundleDTO bundle = ReviewProofBundleDTO.builder()
                .integrity(ReviewIntegrityDTO.builder()
                        .auditChainValid(true)
                        .auditSignatureValid(true)
                        .auditCoverageValid(true)
                        .auditOrderValid(false)
                        .auditOrderViolations(List.of("TASK_COMPLETED_not_last"))
                        .build())
                .runtimeManifest(installedRuntimeManifest())
                .build();
        ReviewProofBundleVerificationDTO verification = ReviewProofBundleVerificationDTO.builder()
                .valid(false)
                .reason("review_integrity_failed")
                .evidenceHashValid(true)
                .reviewStateHashValid(true)
                .runtimeManifestHashValid(true)
                .reviewIntegrityValid(false)
                .auditCoverageValid(true)
                .auditOrderValid(false)
                .bundleHashValid(true)
                .bundleSignatureValid(true)
                .currentStateMatch(true)
                .currentReviewStateMatch(true)
                .currentRuntimeManifestMatch(true)
                .currentBundleMatch(true)
                .build();

        when(findingRepository.findByTaskId(104L)).thenReturn(List.of());
        when(groundingPolicyService.evaluateTaskPolicy(eq(104L), anyList()))
                .thenReturn(ReviewGroundingPolicyDTO.builder()
                        .valid(true)
                        .reason("ok")
                        .violationCount(0)
                        .build());
        when(proofBundleService.buildBundle(104L)).thenReturn(bundle);
        when(proofBundleService.verifyBundleAgainstCurrentState(104L, bundle)).thenReturn(verification);

        QualityGateService.QualityGateResult result = taskService.evaluateQualityGate(104L, "CRITICAL");

        assertFalse(result.isPassed());
        assertTrue(result.isIntegrityBlocked());
        assertEquals("integrity_blocked", result.getReason());
        assertEquals(Boolean.TRUE, result.getAuditCoverageValid());
        assertEquals(Boolean.FALSE, result.getAuditOrderValid());
        assertEquals("review_integrity_failed", result.getProofBundleReason());
    }

    @Test
    void should_block_when_database_append_only_guard_is_missing_for_task() {
        FindingRepository findingRepository = mock(FindingRepository.class);
        ReviewGroundingPolicyService groundingPolicyService = mock(ReviewGroundingPolicyService.class);
        ReviewProofBundleService proofBundleService = mock(ReviewProofBundleService.class);
        QualityGateService taskService = new QualityGateService(
                findingRepository,
                groundingPolicyService,
                proofBundleService
        );

        ReviewProofBundleDTO bundle = ReviewProofBundleDTO.builder()
                .integrity(ReviewIntegrityDTO.builder()
                        .auditChainValid(true)
                        .auditSignatureValid(true)
                        .auditCoverageValid(true)
                        .auditOrderValid(true)
                        .build())
                .runtimeManifest(runtimeManifestWithDatabaseGuard(false, "append_only_guard_missing"))
                .build();
        ReviewProofBundleVerificationDTO verification = ReviewProofBundleVerificationDTO.builder()
                .valid(true)
                .reason("ok")
                .evidenceHashValid(true)
                .reviewStateHashValid(true)
                .runtimeManifestHashValid(true)
                .bundleHashValid(true)
                .bundleSignatureValid(true)
                .currentStateMatch(true)
                .currentReviewStateMatch(true)
                .currentRuntimeManifestMatch(true)
                .currentBundleMatch(true)
                .build();

        when(findingRepository.findByTaskId(103L)).thenReturn(List.of());
        when(groundingPolicyService.evaluateTaskPolicy(eq(103L), anyList()))
                .thenReturn(ReviewGroundingPolicyDTO.builder()
                        .valid(true)
                        .reason("ok")
                        .violationCount(0)
                        .build());
        when(proofBundleService.buildBundle(103L)).thenReturn(bundle);
        when(proofBundleService.verifyBundleAgainstCurrentState(103L, bundle)).thenReturn(verification);

        QualityGateService.QualityGateResult result = taskService.evaluateQualityGate(103L, "CRITICAL");

        assertFalse(result.isPassed());
        assertFalse(result.isSeverityBlocked());
        assertFalse(result.isProvenanceBlocked());
        assertTrue(result.isIntegrityBlocked());
        assertEquals("integrity_blocked", result.getReason());
        assertEquals(Boolean.TRUE, result.getProofBundleValid());
        assertEquals(Boolean.FALSE, result.getRuntimeGuardValid());
        assertEquals(Boolean.FALSE, result.getDbAppendOnlyGuardsInstalled());
        assertEquals("append_only_guard_missing", result.getDbGuardReason());
    }

    private Finding groundedFinding(int severity) {
        return Finding.builder()
                .severity(severity)
                .grounded(true)
                .evidenceCount(1)
                .build();
    }

    private ReviewRuntimeManifestDTO installedRuntimeManifest() {
        return runtimeManifestWithDatabaseGuard(true, "ok");
    }

    private ReviewRuntimeManifestDTO runtimeManifestWithDatabaseGuard(boolean installed, String reason) {
        return ReviewRuntimeManifestDTO.builder()
                .databaseGuards(ReviewRuntimeManifestDTO.DatabaseGuardSnapshot.builder()
                        .querySupported(true)
                        .appendOnlyGuardsInstalled(installed)
                        .updatesBlocked(true)
                        .deletesBlocked(true)
                        .verificationReason(reason)
                        .build())
                .build();
    }
}
