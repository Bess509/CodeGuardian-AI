package com.codeguardian.service.provenance;

import com.codeguardian.dto.ReviewAuditEventDTO;
import com.codeguardian.dto.ReviewEvidenceDTO;
import com.codeguardian.dto.ReviewGroundingPolicyDTO;
import com.codeguardian.dto.ReviewIntegrityDTO;
import com.codeguardian.dto.ReviewProofBundleDTO;
import com.codeguardian.dto.ReviewRuntimeManifestDTO;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ReviewProofBundleHasher {

    private final ProvenanceHashService hashService;
    private final String schemaVersion;

    ReviewProofBundleHasher(ProvenanceHashService hashService, String schemaVersion) {
        this.hashService = hashService;
        this.schemaVersion = schemaVersion;
    }

    String calculateBundleHash(String reviewStateHash,
                               ReviewProofBundleDTO.TaskSnapshot task,
                               ReviewIntegrityDTO integrity,
                               ReviewRuntimeManifestDTO runtimeManifest,
                               ReviewGroundingPolicyDTO groundingPolicy,
                               ReviewProofBundleDTO.Counts counts,
                               List<ReviewAuditEventDTO> auditEvents,
                               List<ReviewEvidenceDTO> evidence,
                               List<ReviewProofBundleDTO.FindingSnapshot> findings) {
        return hashService.hashPayload(proofPayload(
                reviewStateHash,
                task,
                integrity,
                runtimeManifest,
                groundingPolicy,
                counts,
                auditEvents,
                evidence,
                findings
        ));
    }

    String calculateBundleHash(ReviewProofBundleDTO bundle) {
        return calculateBundleHash(
                bundle.getReviewStateHash(),
                bundle.getTask(),
                bundle.getIntegrity(),
                bundle.getRuntimeManifest(),
                bundle.getGroundingPolicy(),
                bundle.getCounts(),
                bundle.getAuditEvents() != null ? bundle.getAuditEvents() : List.of(),
                bundle.getEvidence() != null ? bundle.getEvidence() : List.of(),
                bundle.getFindings() != null ? bundle.getFindings() : List.of()
        );
    }

    String calculateReviewStateHash(ReviewProofBundleDTO bundle) {
        if (bundle == null) {
            return null;
        }
        return calculateReviewStateHash(
                bundle.getTask(),
                bundle.getIntegrity(),
                bundle.getGroundingPolicy(),
                bundle.getCounts(),
                bundle.getAuditEvents() != null ? bundle.getAuditEvents() : List.of(),
                bundle.getEvidence() != null ? bundle.getEvidence() : List.of(),
                bundle.getFindings() != null ? bundle.getFindings() : List.of()
        );
    }

    String calculateReviewStateHash(ReviewProofBundleDTO.TaskSnapshot task,
                                    ReviewIntegrityDTO integrity,
                                    ReviewGroundingPolicyDTO groundingPolicy,
                                    ReviewProofBundleDTO.Counts counts,
                                    List<ReviewAuditEventDTO> auditEvents,
                                    List<ReviewEvidenceDTO> evidence,
                                    List<ReviewProofBundleDTO.FindingSnapshot> findings) {
        return hashService.hashPayload(reviewStatePayload(
                task,
                integrity,
                groundingPolicy,
                counts,
                auditEvents,
                evidence,
                findings
        ));
    }

    List<String> invalidEvidenceRefs(List<ReviewEvidenceDTO> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }
        return evidence.stream()
                .filter(item -> {
                    if (item == null || item.getContentHash() == null || item.getContentHash().isBlank()) {
                        return true;
                    }
                    String expected = hashService.sha256Hex(item.getExcerpt() != null ? item.getExcerpt() : "");
                    return !hashService.secureEquals(expected, item.getContentHash());
                })
                .map(this::evidenceRef)
                .toList();
    }

    boolean samePayload(Object expected, Object provided) {
        return hashService.secureEquals(hashService.hashPayload(expected), hashService.hashPayload(provided));
    }

    String signaturePayload(String bundleHash, String keyId) {
        return "codeguardian-proof-bundle-v1\n"
                + (keyId != null ? keyId : "")
                + "\n"
                + (bundleHash != null ? bundleHash : "");
    }

    private Map<String, Object> proofPayload(String reviewStateHash,
                                             ReviewProofBundleDTO.TaskSnapshot task,
                                             ReviewIntegrityDTO integrity,
                                             ReviewRuntimeManifestDTO runtimeManifest,
                                             ReviewGroundingPolicyDTO groundingPolicy,
                                             ReviewProofBundleDTO.Counts counts,
                                             List<ReviewAuditEventDTO> auditEvents,
                                             List<ReviewEvidenceDTO> evidence,
                                             List<ReviewProofBundleDTO.FindingSnapshot> findings) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", schemaVersion);
        payload.put("reviewStateHashAlgorithm", "SHA-256");
        payload.put("reviewStateHash", reviewStateHash);
        payload.put("task", task);
        payload.put("integrity", integrity);
        payload.put("runtimeManifest", runtimeManifest);
        payload.put("groundingPolicy", groundingPolicy);
        payload.put("counts", counts);
        payload.put("auditEvents", auditEvents);
        payload.put("evidence", evidence);
        payload.put("findings", findings);
        return payload;
    }

    private Map<String, Object> reviewStatePayload(ReviewProofBundleDTO.TaskSnapshot task,
                                                   ReviewIntegrityDTO integrity,
                                                   ReviewGroundingPolicyDTO groundingPolicy,
                                                   ReviewProofBundleDTO.Counts counts,
                                                   List<ReviewAuditEventDTO> auditEvents,
                                                   List<ReviewEvidenceDTO> evidence,
                                                   List<ReviewProofBundleDTO.FindingSnapshot> findings) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", schemaVersion);
        payload.put("task", task);
        payload.put("integrity", integrityStatePayload(integrity));
        payload.put("groundingPolicy", groundingPolicy);
        payload.put("counts", counts);
        payload.put("auditEvents", auditEvents);
        payload.put("evidence", evidence);
        payload.put("findings", findings);
        return payload;
    }

    private Map<String, Object> integrityStatePayload(ReviewIntegrityDTO integrity) {
        if (integrity == null) {
            return Map.of();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", integrity.getTaskId());
        payload.put("auditChainValid", integrity.getAuditChainValid());
        payload.put("auditEventCount", integrity.getAuditEventCount());
        payload.put("failedEventId", integrity.getFailedEventId());
        payload.put("reason", integrity.getReason());
        payload.put("lastAuditHash", integrity.getLastAuditHash());
        payload.put("auditSignatureValid", integrity.getAuditSignatureValid());
        payload.put("signedAuditEventCount", integrity.getSignedAuditEventCount());
        payload.put("signatureKeyId", integrity.getSignatureKeyId());
        payload.put("auditCoverageValid", integrity.getAuditCoverageValid());
        payload.put("missingAuditEventTypes", integrity.getMissingAuditEventTypes());
        payload.put("auditTerminalEventConsistent", integrity.getAuditTerminalEventConsistent());
        payload.put("auditOrderValid", integrity.getAuditOrderValid());
        payload.put("auditOrderViolations", integrity.getAuditOrderViolations());
        payload.put("evidenceCount", integrity.getEvidenceCount());
        payload.put("groundedFindingCount", integrity.getGroundedFindingCount());
        payload.put("totalFindingCount", integrity.getTotalFindingCount());
        payload.put("groundingPolicyValid", integrity.getGroundingPolicyValid());
        payload.put("groundingPolicyVersion", integrity.getGroundingPolicyVersion());
        payload.put("groundingPolicyReason", integrity.getGroundingPolicyReason());
        payload.put("groundingViolationCount", integrity.getGroundingViolationCount());
        return payload;
    }

    private String evidenceRef(ReviewEvidenceDTO evidence) {
        if (evidence == null) {
            return "null";
        }
        if (evidence.getId() != null) {
            return "evidence#" + evidence.getId();
        }
        if (evidence.getSourceRef() != null && !evidence.getSourceRef().isBlank()) {
            return evidence.getSourceRef();
        }
        return evidence.getEvidenceType() != null ? evidence.getEvidenceType() : "unknown";
    }
}
