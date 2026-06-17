package com.codeguardian.service.provenance;

import com.codeguardian.config.AuditSigningProperties;
import com.codeguardian.dto.ReviewProofBundleDTO;
import com.codeguardian.dto.ReviewProofBundleVerificationDTO;
import com.codeguardian.dto.ReviewRuntimeManifestDTO;
import com.codeguardian.entity.Finding;
import com.codeguardian.entity.ReviewAuditEvent;
import com.codeguardian.entity.ReviewEvidence;
import com.codeguardian.entity.ReviewTask;
import com.codeguardian.enums.ReviewTypeEnum;
import com.codeguardian.enums.SeverityEnum;
import com.codeguardian.enums.TaskStatusEnum;
import com.codeguardian.repository.FindingRepository;
import com.codeguardian.repository.ReviewAuditEventRepository;
import com.codeguardian.repository.ReviewEvidenceRepository;
import com.codeguardian.repository.ReviewTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReviewProofBundleServiceTest {

    @Test
    void should_build_stable_proof_bundle_for_review_task() {
        ReviewTaskRepository taskRepository = mock(ReviewTaskRepository.class);
        FindingRepository findingRepository = mock(FindingRepository.class);
        ReviewEvidenceRepository evidenceRepository = mock(ReviewEvidenceRepository.class);
        ReviewAuditEventRepository auditEventRepository = mock(ReviewAuditEventRepository.class);
        ReviewAuditService auditService = mock(ReviewAuditService.class);
        ProvenanceHashService hashService = new ProvenanceHashService(new ObjectMapper().findAndRegisterModules());
        ReviewProofBundleService service = new ReviewProofBundleService(
                taskRepository,
                findingRepository,
                evidenceRepository,
                auditEventRepository,
                auditService,
                hashService
        );

        LocalDateTime now = LocalDateTime.of(2026, 6, 9, 10, 0);
        ReviewTask task = ReviewTask.builder()
                .id(7L)
                .name("payment review")
                .reviewType(ReviewTypeEnum.SNIPPET.getValue())
                .scope("class PaymentService {}")
                .status(TaskStatusEnum.COMPLETED.getValue())
                .createdAt(now)
                .completedAt(now.plusMinutes(1))
                .build();
        String sourceExcerpt = "String sql = \"select * from users where id=\" + id;";
        String sourceContentHash = hashService.sha256Hex(sourceExcerpt);
        String findingEvidenceHash = hashService.sha256Hex(sourceContentHash);
        Finding finding = Finding.builder()
                .id(3L)
                .taskId(7L)
                .severity(SeverityEnum.CRITICAL.getValue())
                .title("SQL injection")
                .location("PaymentService.java:12")
                .startLine(12)
                .endLine(14)
                .description("Raw SQL uses user input")
                .suggestion("Use prepared statement")
                .category("SECURITY")
                .source("AI Model")
                .confidence(0.91)
                .grounded(true)
                .evidenceCount(1)
                .evidenceHash(findingEvidenceHash)
                .groundingSummary("source excerpt and RAG snippet")
                .createdAt(now.plusSeconds(20))
                .build();
        ReviewEvidence evidence = ReviewEvidence.builder()
                .id(11L)
                .taskId(7L)
                .findingId(3L)
                .evidenceType("SOURCE_CODE")
                .sourceName("PaymentService.java")
                .sourceRef("repo://payment/PaymentService.java")
                .locator("PaymentService.java:12-14")
                .startLine(12)
                .endLine(14)
                .excerpt(sourceExcerpt)
                .contentHash(sourceContentHash)
                .relevanceScore(1.0)
                .metadata(Map.of(
                        "language", "Java",
                        "sourceCodeHash", hashService.sha256Hex("class PaymentService {}")
                ))
                .createdAt(now.plusSeconds(30))
                .build();
        ReviewAuditEvent auditEvent = ReviewAuditEvent.builder()
                .id(1L)
                .taskId(7L)
                .eventType("TASK_COMPLETED")
                .stage("ORCHESTRATION")
                .actor("system")
                .message("completed")
                .payloadHash("p".repeat(64))
                .previousHash("0".repeat(64))
                .eventHash("a".repeat(64))
                .signatureKeyId("unit")
                .signatureAlgorithm("HmacSHA256")
                .eventSignature("s".repeat(64))
                .metadata(Map.of("findingCount", 1))
                .createdAt(now.plusSeconds(40))
                .build();

        when(taskRepository.findById(7L)).thenReturn(Optional.of(task));
        when(findingRepository.findByTaskId(7L)).thenReturn(List.of(finding));
        when(evidenceRepository.findByTaskIdOrderByCreatedAtAscIdAsc(7L)).thenReturn(List.of(evidence));
        when(evidenceRepository.countByTaskId(7L)).thenReturn(1L);
        when(auditEventRepository.findByTaskIdOrderByIdAsc(7L)).thenReturn(List.of(auditEvent));
        when(auditService.verifyTaskChain(7L, TaskStatusEnum.COMPLETED.getValue())).thenReturn(ReviewAuditService.IntegrityResult.builder()
                .valid(true)
                .eventCount(1)
                .reason("ok")
                .lastHash("a".repeat(64))
                .signatureValid(true)
                .signedEventCount(1)
                .signatureKeyId("unit")
                .auditCoverageValid(true)
                .missingEventTypes(List.of())
                .terminalEventConsistent(true)
                .auditOrderValid(true)
                .auditOrderViolations(List.of())
                .build());

        ReviewProofBundleDTO first = service.buildBundle(7L);
        ReviewProofBundleDTO second = service.buildBundle(7L);

        assertEquals(ReviewProofBundleService.SCHEMA_VERSION, first.getSchemaVersion());
        assertEquals("SHA-256", first.getReviewStateHashAlgorithm());
        assertNotNull(first.getReviewStateHash());
        assertEquals(first.getReviewStateHash(), second.getReviewStateHash());
        assertEquals("SHA-256", first.getBundleHashAlgorithm());
        assertEquals(first.getBundleHash(), second.getBundleHash());
        assertNotNull(first.getGeneratedAt());
        assertEquals(hashService.sha256Hex(task.getScope()), first.getTask().getScopeHash());
        assertEquals("代码片段", first.getTask().getReviewTypeLabel());
        assertEquals("已完成", first.getTask().getStatusLabel());
        assertEquals(1, first.getCounts().getAuditEventCount());
        assertEquals(1, first.getCounts().getEvidenceCount());
        assertEquals(1, first.getCounts().getFindingCount());
        assertEquals(1L, first.getCounts().getGroundedFindingCount());
        assertEquals(1, first.getCounts().getSignedAuditEventCount());
        assertEquals(0, first.getCounts().getGroundingViolationCount());
        assertEquals("严重", first.getFindings().get(0).getSeverityLabel());
        assertEquals("SOURCE_CODE", first.getEvidence().get(0).getEvidenceType());
        assertEquals("TASK_COMPLETED", first.getAuditEvents().get(0).getEventType());
        assertEquals(Boolean.TRUE, first.getIntegrity().getAuditChainValid());
        assertEquals(Boolean.TRUE, first.getIntegrity().getAuditSignatureValid());
        assertEquals(Boolean.TRUE, first.getIntegrity().getAuditCoverageValid());
        assertEquals(Boolean.TRUE, first.getIntegrity().getAuditOrderValid());
        assertEquals(Boolean.TRUE, first.getIntegrity().getGroundingPolicyValid());
        assertNotNull(first.getRuntimeManifest());
        assertEquals(first.getRuntimeManifest().getManifestHash(), first.getIntegrity().getRuntimeManifestHash());
        assertNotNull(first.getGroundingPolicy());
        assertEquals(Boolean.TRUE, first.getGroundingPolicy().getValid());
        ReviewProofBundleVerificationDTO verification = service.verifyBundle(first);
        assertEquals(Boolean.TRUE, verification.getValid());
        assertEquals(Boolean.TRUE, verification.getEvidenceHashValid());
        assertEquals(Boolean.TRUE, verification.getReviewStateHashValid());
        assertEquals(Boolean.TRUE, verification.getRuntimeManifestHashValid());
        assertEquals(Boolean.TRUE, verification.getGroundingPolicyValid());
        assertEquals(Boolean.TRUE, verification.getReviewIntegrityValid());
        assertEquals(Boolean.TRUE, verification.getAuditCoverageValid());
        assertEquals(Boolean.TRUE, verification.getAuditOrderValid());
        assertEquals(0, verification.getInvalidEvidenceCount());
        assertEquals(0, verification.getGroundingViolationCount());

        ReviewProofBundleVerificationDTO currentVerification = service.verifyBundleAgainstCurrentState(7L, first);
        assertEquals(Boolean.TRUE, currentVerification.getValid());
        assertEquals(Boolean.TRUE, currentVerification.getCurrentStateMatch());
        assertEquals(first.getReviewStateHash(), currentVerification.getCurrentReviewStateHash());

        finding.setTitle("Changed title in current database");
        ReviewProofBundleVerificationDTO driftVerification = service.verifyBundleAgainstCurrentState(7L, first);
        assertEquals(Boolean.FALSE, driftVerification.getValid());
        assertEquals(Boolean.FALSE, driftVerification.getCurrentStateMatch());
        assertEquals("current_state_mismatch", driftVerification.getReason());
        finding.setTitle("SQL injection");

        first.getRuntimeManifest().getRag().setReviewRetrievalTopK(9);
        ReviewProofBundleVerificationDTO tamperedManifest = service.verifyBundle(first);
        assertEquals(Boolean.FALSE, tamperedManifest.getValid());
        assertEquals(Boolean.FALSE, tamperedManifest.getRuntimeManifestHashValid());
        assertEquals("runtime_manifest_hash_mismatch", tamperedManifest.getReason());
        first.getRuntimeManifest().getRag().setReviewRetrievalTopK(8);

        first.getGroundingPolicy().setViolationCount(99);
        ReviewProofBundleVerificationDTO tamperedPolicy = service.verifyBundle(first);
        assertEquals(Boolean.FALSE, tamperedPolicy.getValid());
        assertEquals(Boolean.FALSE, tamperedPolicy.getGroundingPolicyValid());
        assertEquals("grounding_policy_mismatch", tamperedPolicy.getReason());
        first.getGroundingPolicy().setViolationCount(0);

        first.getEvidence().get(0).setExcerpt("tampered evidence excerpt");
        ReviewProofBundleVerificationDTO tamperedEvidence = service.verifyBundle(first);
        assertEquals(Boolean.FALSE, tamperedEvidence.getValid());
        assertEquals(Boolean.FALSE, tamperedEvidence.getEvidenceHashValid());
        assertEquals(Boolean.FALSE, tamperedEvidence.getGroundingPolicyValid());
        assertEquals("evidence_hash_mismatch", tamperedEvidence.getReason());
        assertEquals(List.of("evidence#11"), tamperedEvidence.getInvalidEvidenceRefs());
    }

    @Test
    void should_sign_and_verify_proof_bundle_when_signing_is_enabled() {
        ReviewTaskRepository taskRepository = mock(ReviewTaskRepository.class);
        FindingRepository findingRepository = mock(FindingRepository.class);
        ReviewEvidenceRepository evidenceRepository = mock(ReviewEvidenceRepository.class);
        ReviewAuditEventRepository auditEventRepository = mock(ReviewAuditEventRepository.class);
        ReviewAuditService auditService = mock(ReviewAuditService.class);
        ProvenanceHashService hashService = new ProvenanceHashService(new ObjectMapper().findAndRegisterModules());
        AuditSigningProperties signing = signingProperties();
        ReviewProofBundleService service = new ReviewProofBundleService(
                taskRepository,
                findingRepository,
                evidenceRepository,
                auditEventRepository,
                auditService,
                hashService,
                signing
        );

        ReviewTask task = ReviewTask.builder()
                .id(8L)
                .name("signed bundle")
                .reviewType(ReviewTypeEnum.FILE.getValue())
                .scope("/repo/PaymentService.java")
                .status(TaskStatusEnum.COMPLETED.getValue())
                .createdAt(LocalDateTime.of(2026, 6, 9, 11, 0))
                .completedAt(LocalDateTime.of(2026, 6, 9, 11, 1))
                .build();

        when(taskRepository.findById(8L)).thenReturn(Optional.of(task));
        when(findingRepository.findByTaskId(8L)).thenReturn(List.of());
        when(evidenceRepository.findByTaskIdOrderByCreatedAtAscIdAsc(8L)).thenReturn(List.of());
        when(evidenceRepository.countByTaskId(8L)).thenReturn(0L);
        when(auditEventRepository.findByTaskIdOrderByIdAsc(8L)).thenReturn(List.of());
        when(auditService.verifyTaskChain(8L, TaskStatusEnum.COMPLETED.getValue())).thenReturn(ReviewAuditService.IntegrityResult.builder()
                .valid(true)
                .eventCount(0)
                .reason("ok")
                .lastHash("0".repeat(64))
                .signatureValid(true)
                .signedEventCount(0)
                .signatureKeyId("bundle-key")
                .auditCoverageValid(true)
                .missingEventTypes(List.of())
                .terminalEventConsistent(true)
                .auditOrderValid(true)
                .auditOrderViolations(List.of())
                .build());

        ReviewProofBundleDTO bundle = service.buildBundle(8L);

        assertEquals("bundle-key", bundle.getBundleSignatureKeyId());
        assertEquals("HmacSHA256", bundle.getBundleSignatureAlgorithm());
        assertNotNull(bundle.getBundleSignature());
        assertEquals(64, bundle.getBundleSignature().length());
        assertNotNull(bundle.getReviewStateHash());
        assertNotNull(bundle.getRuntimeManifest());
        assertNotNull(bundle.getRuntimeManifest().getManifestHash());
        assertNotNull(bundle.getGroundingPolicy());
        assertEquals(Boolean.TRUE, bundle.getGroundingPolicy().getValid());
        assertTrue(service.verifyBundleSignature(bundle));
        ReviewProofBundleVerificationDTO verification = service.verifyBundle(bundle);
        assertEquals(Boolean.TRUE, verification.getValid());
        assertEquals(Boolean.TRUE, verification.getReviewStateHashValid());
        assertEquals(Boolean.TRUE, verification.getRuntimeManifestHashValid());
        assertEquals(Boolean.TRUE, verification.getGroundingPolicyValid());
        assertEquals(Boolean.TRUE, verification.getReviewIntegrityValid());
        assertEquals(Boolean.TRUE, verification.getBundleHashValid());
        assertEquals(Boolean.TRUE, verification.getBundleSignatureValid());
        assertEquals(Boolean.TRUE, verification.getAuditOrderValid());
        assertEquals("ok", verification.getReason());

        String originalSignature = bundle.getBundleSignature();
        bundle.setBundleSignature("bad");
        assertFalse(service.verifyBundleSignature(bundle));
        ReviewProofBundleVerificationDTO badSignature = service.verifyBundle(bundle);
        assertEquals(Boolean.FALSE, badSignature.getValid());
        assertEquals(Boolean.TRUE, badSignature.getBundleHashValid());
        assertEquals(Boolean.FALSE, badSignature.getBundleSignatureValid());
        assertEquals("bundle_signature_invalid", badSignature.getReason());

        bundle.setBundleSignature(originalSignature);
        bundle.getTask().setName("tampered bundle");
        ReviewProofBundleVerificationDTO tamperedContent = service.verifyBundle(bundle);
        assertEquals(Boolean.FALSE, tamperedContent.getValid());
        assertEquals(Boolean.FALSE, tamperedContent.getReviewStateHashValid());
        assertEquals(Boolean.FALSE, tamperedContent.getBundleHashValid());
        assertEquals(Boolean.TRUE, tamperedContent.getBundleSignatureValid());
        assertEquals("review_state_hash_mismatch", tamperedContent.getReason());

        bundle.getTask().setName("signed bundle");
        String originalHash = bundle.getBundleHash();
        bundle.setBundleHash("bad");
        ReviewProofBundleVerificationDTO badBundleHash = service.verifyBundle(bundle);
        assertEquals(Boolean.FALSE, badBundleHash.getValid());
        assertEquals(Boolean.FALSE, badBundleHash.getBundleHashValid());
        assertEquals("bundle_hash_mismatch", badBundleHash.getReason());
        bundle.setBundleHash(originalHash);
        String originalKeyId = bundle.getBundleSignatureKeyId();
        bundle.setBundleSignatureKeyId("other-key");
        assertFalse(service.verifyBundleSignature(bundle));
        ReviewProofBundleVerificationDTO badKey = service.verifyBundle(bundle);
        assertEquals(Boolean.FALSE, badKey.getValid());
        assertEquals(Boolean.TRUE, badKey.getBundleHashValid());
        assertEquals(Boolean.FALSE, badKey.getBundleSignatureValid());
        assertEquals("bundle_signature_invalid", badKey.getReason());
        bundle.setBundleSignatureKeyId(originalKeyId);
    }

    @Test
    void should_detect_current_runtime_manifest_drift_even_when_review_state_matches() {
        ReviewTaskRepository taskRepository = mock(ReviewTaskRepository.class);
        FindingRepository findingRepository = mock(FindingRepository.class);
        ReviewEvidenceRepository evidenceRepository = mock(ReviewEvidenceRepository.class);
        ReviewAuditEventRepository auditEventRepository = mock(ReviewAuditEventRepository.class);
        ReviewAuditService auditService = mock(ReviewAuditService.class);
        ReviewRuntimeManifestService runtimeManifestService = mock(ReviewRuntimeManifestService.class);
        ProvenanceHashService hashService = new ProvenanceHashService(new ObjectMapper().findAndRegisterModules());
        ReviewProofBundleService service = new ReviewProofBundleService(
                taskRepository,
                findingRepository,
                evidenceRepository,
                auditEventRepository,
                auditService,
                null,
                runtimeManifestService,
                hashService,
                new AuditSigningProperties()
        );
        ReviewTask task = ReviewTask.builder()
                .id(9L)
                .name("runtime drift")
                .reviewType(ReviewTypeEnum.SNIPPET.getValue())
                .scope("class Stable {}")
                .status(TaskStatusEnum.COMPLETED.getValue())
                .createdAt(LocalDateTime.of(2026, 6, 9, 12, 0))
                .completedAt(LocalDateTime.of(2026, 6, 9, 12, 1))
                .build();
        ReviewRuntimeManifestDTO originalManifest = runtimeManifest(hashService, "installed", true);
        ReviewRuntimeManifestDTO currentManifest = runtimeManifest(hashService, "missing", false);

        when(taskRepository.findById(9L)).thenReturn(Optional.of(task));
        when(findingRepository.findByTaskId(9L)).thenReturn(List.of());
        when(evidenceRepository.findByTaskIdOrderByCreatedAtAscIdAsc(9L)).thenReturn(List.of());
        when(evidenceRepository.countByTaskId(9L)).thenReturn(0L);
        when(auditEventRepository.findByTaskIdOrderByIdAsc(9L)).thenReturn(List.of());
        when(auditService.verifyTaskChain(9L, TaskStatusEnum.COMPLETED.getValue()))
                .thenReturn(ReviewAuditService.IntegrityResult.builder()
                        .valid(true)
                        .eventCount(0)
                        .reason("ok")
                        .lastHash("0".repeat(64))
                        .signatureValid(true)
                        .signedEventCount(0)
                        .signatureKeyId("unit")
                        .auditCoverageValid(true)
                        .missingEventTypes(List.of())
                        .terminalEventConsistent(true)
                        .auditOrderValid(true)
                        .auditOrderViolations(List.of())
                        .build());
        when(runtimeManifestService.buildManifest()).thenReturn(originalManifest, currentManifest);
        when(runtimeManifestService.verifyManifestHash(originalManifest)).thenReturn(true);
        when(runtimeManifestService.verifyManifestHash(currentManifest)).thenReturn(true);

        ReviewProofBundleDTO bundle = service.buildBundle(9L);
        ReviewProofBundleVerificationDTO verification = service.verifyBundleAgainstCurrentState(9L, bundle);

        assertEquals(Boolean.FALSE, verification.getValid());
        assertEquals(Boolean.TRUE, verification.getReviewStateHashValid());
        assertEquals(Boolean.TRUE, verification.getCurrentReviewStateMatch());
        assertEquals(Boolean.FALSE, verification.getCurrentRuntimeManifestMatch());
        assertEquals(Boolean.FALSE, verification.getCurrentBundleMatch());
        assertEquals(Boolean.FALSE, verification.getCurrentStateMatch());
        assertEquals("current_runtime_manifest_mismatch", verification.getReason());
        assertEquals(bundle.getReviewStateHash(), verification.getCurrentReviewStateHash());
        assertEquals(originalManifest.getManifestHash(), verification.getRuntimeManifestHash());
        assertEquals(currentManifest.getManifestHash(), verification.getCurrentRuntimeManifestHash());
        assertEquals(bundle.getBundleHash(), verification.getProvidedBundleHash());
        assertNotNull(verification.getCurrentBundleHash());
        assertFalse(verification.getCurrentBundleHash().equals(bundle.getBundleHash()));
    }

    @Test
    void should_fail_when_task_does_not_exist() {
        ReviewTaskRepository taskRepository = mock(ReviewTaskRepository.class);
        ReviewProofBundleService service = new ReviewProofBundleService(
                taskRepository,
                mock(FindingRepository.class),
                mock(ReviewEvidenceRepository.class),
                mock(ReviewAuditEventRepository.class),
                mock(ReviewAuditService.class),
                new ProvenanceHashService(new ObjectMapper().findAndRegisterModules())
        );
        when(taskRepository.findById(404L)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> service.buildBundle(404L));
    }

    private AuditSigningProperties signingProperties() {
        AuditSigningProperties signing = new AuditSigningProperties();
        signing.setEnabled(true);
        signing.setKeyId("bundle-key");
        signing.setSecret("super-secret-test-key");
        return signing;
    }

    private ReviewRuntimeManifestDTO runtimeManifest(ProvenanceHashService hashService,
                                                     String reason,
                                                     boolean guardInstalled) {
        ReviewRuntimeManifestDTO manifest = ReviewRuntimeManifestDTO.builder()
                .manifestVersion(ReviewRuntimeManifestService.MANIFEST_VERSION)
                .manifestHashAlgorithm("SHA-256")
                .databaseGuards(ReviewRuntimeManifestDTO.DatabaseGuardSnapshot.builder()
                        .appendOnlyGuardsInstalled(guardInstalled)
                        .expectedTriggerCount(2)
                        .installedTriggerCount(guardInstalled ? 2 : 1)
                        .verificationReason(reason)
                        .build())
                .build();
        manifest.setManifestHash(hashService.hashPayload(Map.of(
                "manifestVersion", manifest.getManifestVersion(),
                "manifestHashAlgorithm", manifest.getManifestHashAlgorithm(),
                "databaseGuards", manifest.getDatabaseGuards()
        )));
        return manifest;
    }
}
