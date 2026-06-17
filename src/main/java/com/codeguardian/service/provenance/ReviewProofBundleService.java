package com.codeguardian.service.provenance;

import com.codeguardian.config.AuditSigningProperties;
import com.codeguardian.dto.ReviewAuditEventDTO;
import com.codeguardian.dto.ReviewEvidenceDTO;
import com.codeguardian.dto.ReviewGroundingPolicyDTO;
import com.codeguardian.dto.ReviewIntegrityDTO;
import com.codeguardian.dto.ReviewProofBundleDTO;
import com.codeguardian.dto.ReviewProofBundleVerificationDTO;
import com.codeguardian.dto.ReviewRuntimeManifestDTO;
import com.codeguardian.entity.Finding;
import com.codeguardian.entity.ReviewAuditEvent;
import com.codeguardian.entity.ReviewEvidence;
import com.codeguardian.entity.ReviewTask;
import com.codeguardian.repository.FindingRepository;
import com.codeguardian.repository.ReviewAuditEventRepository;
import com.codeguardian.repository.ReviewEvidenceRepository;
import com.codeguardian.repository.ReviewTaskRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Service
public class ReviewProofBundleService {

    public static final String SCHEMA_VERSION = "codeguardian-review-proof-bundle-v1";

    private final ReviewTaskRepository taskRepository;
    private final FindingRepository findingRepository;
    private final ReviewEvidenceRepository evidenceRepository;
    private final ReviewAuditEventRepository auditEventRepository;
    private final ReviewAuditService auditService;
    private final ReviewGroundingPolicyService groundingPolicyService;
    private final ReviewRuntimeManifestService runtimeManifestService;
    private final ProvenanceHashService hashService;
    private final AuditSigningProperties signingProperties;
    private final ReviewProofBundleMapper bundleMapper;
    private final ReviewProofBundleHasher bundleHasher;

    @Autowired
    public ReviewProofBundleService(ReviewTaskRepository taskRepository,
                                    FindingRepository findingRepository,
                                    ReviewEvidenceRepository evidenceRepository,
                                    ReviewAuditEventRepository auditEventRepository,
                                    ReviewAuditService auditService,
                                    ReviewGroundingPolicyService groundingPolicyService,
                                    ReviewRuntimeManifestService runtimeManifestService,
                                    ProvenanceHashService hashService,
                                    AuditSigningProperties signingProperties) {
        this.taskRepository = taskRepository;
        this.findingRepository = findingRepository;
        this.evidenceRepository = evidenceRepository;
        this.auditEventRepository = auditEventRepository;
        this.auditService = auditService;
        this.hashService = hashService;
        this.groundingPolicyService = groundingPolicyService != null
                ? groundingPolicyService
                : new ReviewGroundingPolicyService(evidenceRepository, hashService);
        this.runtimeManifestService = runtimeManifestService != null
                ? runtimeManifestService
                : new ReviewRuntimeManifestService(hashService);
        this.signingProperties = signingProperties != null ? signingProperties : new AuditSigningProperties();
        this.bundleMapper = new ReviewProofBundleMapper(hashService);
        this.bundleHasher = new ReviewProofBundleHasher(hashService, SCHEMA_VERSION);
    }

    public ReviewProofBundleService(ReviewTaskRepository taskRepository,
                                    FindingRepository findingRepository,
                                    ReviewEvidenceRepository evidenceRepository,
                                    ReviewAuditEventRepository auditEventRepository,
                                    ReviewAuditService auditService,
                                    ProvenanceHashService hashService,
                                    AuditSigningProperties signingProperties) {
        this(taskRepository, findingRepository, evidenceRepository, auditEventRepository, auditService, null, null,
                hashService, signingProperties);
    }

    public ReviewProofBundleService(ReviewTaskRepository taskRepository,
                                    FindingRepository findingRepository,
                                    ReviewEvidenceRepository evidenceRepository,
                                    ReviewAuditEventRepository auditEventRepository,
                                    ReviewAuditService auditService,
                                    ProvenanceHashService hashService) {
        this(taskRepository, findingRepository, evidenceRepository, auditEventRepository, auditService, null, null, hashService,
                new AuditSigningProperties());
    }

    public ReviewProofBundleDTO buildBundle(Long taskId) {
        ReviewTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("任务不存在: " + taskId));
        List<Finding> findings = findingRepository.findByTaskId(taskId).stream()
                .sorted(Comparator.comparing(Finding::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();
        List<ReviewEvidence> evidence = evidenceRepository.findByTaskIdOrderByCreatedAtAscIdAsc(taskId);
        List<ReviewAuditEvent> auditEvents = auditEventRepository.findByTaskIdOrderByIdAsc(taskId);
        ReviewRuntimeManifestDTO runtimeManifest = runtimeManifestService.buildManifest();
        ReviewGroundingPolicyDTO groundingPolicy = groundingPolicyService.evaluatePolicy(findings, evidence);
        ReviewIntegrityDTO integrity = buildIntegrity(taskId, task.getStatus(), findings, groundingPolicy, runtimeManifest);
        long groundedFindings = findings.stream()
                .filter(finding -> Boolean.TRUE.equals(finding.getGrounded()))
                .count();

        List<ReviewAuditEventDTO> auditDtos = auditEvents.stream().map(bundleMapper::toAuditDTO).toList();
        List<ReviewEvidenceDTO> evidenceDtos = evidence.stream().map(bundleMapper::toEvidenceDTO).toList();
        List<ReviewProofBundleDTO.FindingSnapshot> findingDtos = findings.stream().map(bundleMapper::toFindingSnapshot).toList();
        ReviewProofBundleDTO.TaskSnapshot taskSnapshot = bundleMapper.toTaskSnapshot(task);
        ReviewProofBundleDTO.Counts counts = ReviewProofBundleDTO.Counts.builder()
                .auditEventCount(auditDtos.size())
                .evidenceCount(evidenceDtos.size())
                .findingCount(findingDtos.size())
                .groundedFindingCount(groundedFindings)
                .signedAuditEventCount(integrity.getSignedAuditEventCount())
                .groundingViolationCount(groundingPolicy.getViolationCount())
                .build();
        String reviewStateHash = bundleHasher.calculateReviewStateHash(
                taskSnapshot,
                integrity,
                groundingPolicy,
                counts,
                auditDtos,
                evidenceDtos,
                findingDtos
        );
        String bundleHash = bundleHasher.calculateBundleHash(
                reviewStateHash,
                taskSnapshot,
                integrity,
                runtimeManifest,
                groundingPolicy,
                counts,
                auditDtos,
                evidenceDtos,
                findingDtos
        );
        String bundleSignatureKeyId = null;
        String bundleSignatureAlgorithm = null;
        String bundleSignature = null;
        if (signingProperties.isActive()) {
            bundleSignatureKeyId = signingProperties.getKeyId();
            bundleSignatureAlgorithm = signingProperties.getAlgorithm();
            bundleSignature = hashService.hmacSha256Hex(
                    bundleHasher.signaturePayload(bundleHash, bundleSignatureKeyId),
                    signingProperties.getSecret());
        }

        return ReviewProofBundleDTO.builder()
                .schemaVersion(SCHEMA_VERSION)
                .generatedAt(LocalDateTime.now())
                .reviewStateHashAlgorithm("SHA-256")
                .reviewStateHash(reviewStateHash)
                .bundleHashAlgorithm("SHA-256")
                .bundleHash(bundleHash)
                .bundleSignatureKeyId(bundleSignatureKeyId)
                .bundleSignatureAlgorithm(bundleSignatureAlgorithm)
                .bundleSignature(bundleSignature)
                .task(taskSnapshot)
                .integrity(integrity)
                .runtimeManifest(runtimeManifest)
                .groundingPolicy(groundingPolicy)
                .counts(counts)
                .auditEvents(auditDtos)
                .evidence(evidenceDtos)
                .findings(findingDtos)
                .build();
    }

    public boolean verifyBundleSignature(ReviewProofBundleDTO bundle) {
        if (bundle == null) {
            return false;
        }
        if (!signingProperties.isActive()) {
            return bundle.getBundleSignature() == null || bundle.getBundleSignature().isBlank();
        }
        if (bundle.getBundleHash() == null || bundle.getBundleSignature() == null
                || bundle.getBundleSignatureKeyId() == null) {
            return false;
        }
        if (!bundle.getBundleSignatureKeyId().equals(signingProperties.getKeyId())) {
            return false;
        }
        String expected = hashService.hmacSha256Hex(
                bundleHasher.signaturePayload(bundle.getBundleHash(), bundle.getBundleSignatureKeyId()),
                signingProperties.getSecret());
        return hashService.secureEquals(expected, bundle.getBundleSignature());
    }

    public ReviewProofBundleVerificationDTO verifyBundle(ReviewProofBundleDTO bundle) {
        if (bundle == null) {
            return ReviewProofBundleVerificationDTO.builder()
                    .valid(false)
                    .schemaVersionValid(false)
                    .evidenceHashValid(false)
                    .reviewStateHashValid(false)
                    .runtimeManifestHashValid(false)
                    .groundingPolicyValid(false)
                    .reviewIntegrityValid(false)
                    .auditChainValid(false)
                    .auditSignatureValid(false)
                    .auditCoverageValid(false)
                    .auditOrderValid(false)
                    .bundleHashValid(false)
                    .bundleSignatureValid(false)
                    .reason("bundle_missing")
                    .evidenceCount(0)
                    .invalidEvidenceCount(0)
                    .invalidEvidenceRefs(List.of())
                    .groundingViolationCount(0)
                    .build();
        }

        boolean schemaValid = SCHEMA_VERSION.equals(bundle.getSchemaVersion());
        List<String> invalidEvidenceRefs = bundleHasher.invalidEvidenceRefs(bundle.getEvidence());
        boolean evidenceHashValid = invalidEvidenceRefs.isEmpty();
        String expectedReviewStateHash = bundleHasher.calculateReviewStateHash(bundle);
        boolean reviewStateHashValid = hashService.secureEquals(expectedReviewStateHash, bundle.getReviewStateHash());
        boolean runtimeManifestHashValid = runtimeManifestService.verifyManifestHash(bundle.getRuntimeManifest());
        ReviewGroundingPolicyDTO expectedPolicy = groundingPolicyService.evaluateBundlePolicy(
                bundle.getFindings(),
                bundle.getEvidence()
        );
        boolean policySnapshotValid = bundleHasher.samePayload(expectedPolicy, bundle.getGroundingPolicy());
        boolean policyPassed = Boolean.TRUE.equals(expectedPolicy.getValid());
        boolean groundingPolicyValid = policySnapshotValid && policyPassed;
        boolean auditChainValid = bundle.getIntegrity() != null
                && Boolean.TRUE.equals(bundle.getIntegrity().getAuditChainValid());
        boolean auditSignatureValid = bundle.getIntegrity() != null
                && Boolean.TRUE.equals(bundle.getIntegrity().getAuditSignatureValid());
        boolean auditCoverageValid = bundle.getIntegrity() != null
                && Boolean.TRUE.equals(bundle.getIntegrity().getAuditCoverageValid());
        boolean auditOrderValid = bundle.getIntegrity() != null
                && Boolean.TRUE.equals(bundle.getIntegrity().getAuditOrderValid());
        boolean reviewIntegrityValid = auditChainValid && auditSignatureValid && auditCoverageValid && auditOrderValid;
        String expectedHash = bundleHasher.calculateBundleHash(bundle);
        boolean hashValid = hashService.secureEquals(expectedHash, bundle.getBundleHash());
        boolean signatureValid = verifyBundleSignature(bundle);
        String reason = "ok";
        if (!schemaValid) {
            reason = "schema_version_mismatch";
        } else if (!evidenceHashValid) {
            reason = "evidence_hash_mismatch";
        } else if (!runtimeManifestHashValid) {
            reason = "runtime_manifest_hash_mismatch";
        } else if (!policySnapshotValid) {
            reason = "grounding_policy_mismatch";
        } else if (!policyPassed) {
            reason = "grounding_policy_failed";
        } else if (!reviewStateHashValid) {
            reason = "review_state_hash_mismatch";
        } else if (!reviewIntegrityValid) {
            reason = "review_integrity_failed";
        } else if (!hashValid) {
            reason = "bundle_hash_mismatch";
        } else if (!signatureValid) {
            reason = "bundle_signature_invalid";
        }

        return ReviewProofBundleVerificationDTO.builder()
                .valid(schemaValid && evidenceHashValid && reviewStateHashValid && runtimeManifestHashValid
                        && groundingPolicyValid && reviewIntegrityValid && hashValid && signatureValid)
                .schemaVersionValid(schemaValid)
                .evidenceHashValid(evidenceHashValid)
                .reviewStateHashValid(reviewStateHashValid)
                .runtimeManifestHashValid(runtimeManifestHashValid)
                .groundingPolicyValid(groundingPolicyValid)
                .reviewIntegrityValid(reviewIntegrityValid)
                .auditChainValid(auditChainValid)
                .auditSignatureValid(auditSignatureValid)
                .auditCoverageValid(auditCoverageValid)
                .auditOrderValid(auditOrderValid)
                .bundleHashValid(hashValid)
                .bundleSignatureValid(signatureValid)
                .reason(reason)
                .schemaVersion(bundle.getSchemaVersion())
                .evidenceCount(bundle.getEvidence() != null ? bundle.getEvidence().size() : 0)
                .invalidEvidenceCount(invalidEvidenceRefs.size())
                .invalidEvidenceRefs(invalidEvidenceRefs)
                .groundingViolationCount(expectedPolicy.getViolationCount())
                .expectedReviewStateHash(expectedReviewStateHash)
                .providedReviewStateHash(bundle.getReviewStateHash())
                .runtimeManifestHash(bundle.getRuntimeManifest() != null ? bundle.getRuntimeManifest().getManifestHash() : null)
                .expectedBundleHash(expectedHash)
                .providedBundleHash(bundle.getBundleHash())
                .bundleSignatureKeyId(bundle.getBundleSignatureKeyId())
                .bundleSignatureAlgorithm(bundle.getBundleSignatureAlgorithm())
                .build();
    }

    public ReviewProofBundleVerificationDTO verifyBundleAgainstCurrentState(Long taskId, ReviewProofBundleDTO bundle) {
        ReviewProofBundleVerificationDTO verification = verifyBundle(bundle);
        verification.setCurrentTaskId(taskId);
        if (taskId == null || bundle == null || bundle.getTask() == null || bundle.getTask().getId() == null) {
            verification.setCurrentStateMatch(false);
            verification.setValid(false);
            if ("ok".equals(verification.getReason())) {
                verification.setReason("current_task_missing");
            }
            return verification;
        }
        if (!taskId.equals(bundle.getTask().getId())) {
            verification.setCurrentStateMatch(false);
            verification.setValid(false);
            verification.setReason("task_id_mismatch");
            return verification;
        }
        try {
            ReviewProofBundleDTO currentBundle = buildBundle(taskId);
            verification.setCurrentReviewStateHash(currentBundle.getReviewStateHash());
            verification.setCurrentBundleHash(currentBundle.getBundleHash());
            verification.setCurrentRuntimeManifestHash(currentBundle.getRuntimeManifest() != null
                    ? currentBundle.getRuntimeManifest().getManifestHash() : null);
            boolean reviewStateMatch = hashService.secureEquals(
                    currentBundle.getReviewStateHash(),
                    bundle.getReviewStateHash()
            );
            boolean runtimeManifestMatch = hashService.secureEquals(
                    currentBundle.getRuntimeManifest() != null ? currentBundle.getRuntimeManifest().getManifestHash() : null,
                    bundle.getRuntimeManifest() != null ? bundle.getRuntimeManifest().getManifestHash() : null
            );
            boolean bundleMatch = hashService.secureEquals(currentBundle.getBundleHash(), bundle.getBundleHash());
            verification.setCurrentReviewStateMatch(reviewStateMatch);
            verification.setCurrentRuntimeManifestMatch(runtimeManifestMatch);
            verification.setCurrentBundleMatch(bundleMatch);
            boolean currentMatch = reviewStateMatch && runtimeManifestMatch && bundleMatch;
            verification.setCurrentStateMatch(currentMatch);
            verification.setValid(Boolean.TRUE.equals(verification.getValid()) && currentMatch);
            if (!currentMatch && "ok".equals(verification.getReason())) {
                if (!reviewStateMatch) {
                    verification.setReason("current_state_mismatch");
                } else if (!runtimeManifestMatch) {
                    verification.setReason("current_runtime_manifest_mismatch");
                } else {
                    verification.setReason("current_bundle_mismatch");
                }
            }
        } catch (Exception e) {
            verification.setCurrentStateMatch(false);
            verification.setCurrentReviewStateMatch(false);
            verification.setCurrentRuntimeManifestMatch(false);
            verification.setCurrentBundleMatch(false);
            verification.setValid(false);
            if ("ok".equals(verification.getReason())) {
                verification.setReason("current_task_missing");
            }
        }
        return verification;
    }

    public ReviewIntegrityDTO buildIntegrity(Long taskId) {
        List<Finding> findings = findingRepository.findByTaskId(taskId);
        List<ReviewEvidence> evidence = evidenceRepository.findByTaskIdOrderByCreatedAtAscIdAsc(taskId);
        ReviewRuntimeManifestDTO runtimeManifest = runtimeManifestService.buildManifest();
        ReviewGroundingPolicyDTO groundingPolicy = groundingPolicyService.evaluatePolicy(findings, evidence);
        Integer taskStatus = taskRepository.findById(taskId).map(ReviewTask::getStatus).orElse(null);
        return buildIntegrity(taskId, taskStatus, findings, groundingPolicy, runtimeManifest);
    }

    private ReviewIntegrityDTO buildIntegrity(Long taskId,
                                              Integer taskStatus,
                                              List<Finding> findings,
                                              ReviewGroundingPolicyDTO groundingPolicy,
                                              ReviewRuntimeManifestDTO runtimeManifest) {
        ReviewAuditService.IntegrityResult result = auditService.verifyTaskChain(taskId, taskStatus);
        long grounded = findings.stream().filter(f -> Boolean.TRUE.equals(f.getGrounded())).count();
        return ReviewIntegrityDTO.builder()
                .taskId(taskId)
                .auditChainValid(result.isValid())
                .auditEventCount(result.getEventCount())
                .failedEventId(result.getFailedEventId())
                .reason(result.getReason())
                .lastAuditHash(result.getLastHash())
                .auditSignatureValid(result.isSignatureValid())
                .signedAuditEventCount(result.getSignedEventCount())
                .signatureKeyId(result.getSignatureKeyId())
                .auditCoverageValid(result.isAuditCoverageValid())
                .missingAuditEventTypes(result.getMissingEventTypes())
                .auditTerminalEventConsistent(result.isTerminalEventConsistent())
                .auditOrderValid(result.isAuditOrderValid())
                .auditOrderViolations(result.getAuditOrderViolations())
                .evidenceCount(evidenceRepository.countByTaskId(taskId))
                .groundedFindingCount(grounded)
                .totalFindingCount((long) findings.size())
                .groundingPolicyValid(groundingPolicy.getValid())
                .groundingPolicyVersion(groundingPolicy.getPolicyVersion())
                .groundingPolicyReason(groundingPolicy.getReason())
                .groundingViolationCount(groundingPolicy.getViolationCount())
                .runtimeManifestVersion(runtimeManifest.getManifestVersion())
                .runtimeManifestHash(runtimeManifest.getManifestHash())
                .build();
    }

}
