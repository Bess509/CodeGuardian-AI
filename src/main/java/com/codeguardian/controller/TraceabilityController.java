package com.codeguardian.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.codeguardian.dto.ReviewAuditEventDTO;
import com.codeguardian.dto.ReviewAssuranceSummaryDTO;
import com.codeguardian.dto.ReviewEvidenceDTO;
import com.codeguardian.dto.ReviewGroundingPolicyDTO;
import com.codeguardian.dto.ReviewIntegrityDTO;
import com.codeguardian.dto.ReviewProofBundleArchiveDTO;
import com.codeguardian.dto.ReviewProofBundleDTO;
import com.codeguardian.dto.ReviewProofBundleVerificationDTO;
import com.codeguardian.dto.ReviewRuntimeManifestDTO;
import com.codeguardian.dto.ReviewTraceGraphDTO;
import com.codeguardian.entity.ReviewAuditEvent;
import com.codeguardian.entity.ReviewEvidence;
import com.codeguardian.repository.ReviewAuditEventRepository;
import com.codeguardian.repository.ReviewEvidenceRepository;
import com.codeguardian.service.provenance.ReviewAssuranceService;
import com.codeguardian.service.provenance.ReviewProofBundleArchiveService;
import com.codeguardian.service.provenance.ReviewProofBundleService;
import com.codeguardian.service.provenance.ReviewTraceGraphService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/review")
@RequiredArgsConstructor
public class TraceabilityController {

    private final ReviewEvidenceRepository evidenceRepository;
    private final ReviewAuditEventRepository auditEventRepository;
    private final ReviewProofBundleService proofBundleService;
    private final ReviewTraceGraphService traceGraphService;
    private final ReviewAssuranceService assuranceService;
    private final ReviewProofBundleArchiveService proofBundleArchiveService;

    @GetMapping("/task/{taskId}/evidence")
    @SaCheckPermission("QUERY")
    public ResponseEntity<List<ReviewEvidenceDTO>> getTaskEvidence(@PathVariable Long taskId) {
        return ResponseEntity.ok(evidenceRepository.findByTaskIdOrderByCreatedAtAscIdAsc(taskId)
                .stream()
                .map(this::toEvidenceDTO)
                .toList());
    }

    @GetMapping("/finding/{findingId}/evidence")
    @SaCheckPermission("QUERY")
    public ResponseEntity<List<ReviewEvidenceDTO>> getFindingEvidence(@PathVariable Long findingId) {
        return ResponseEntity.ok(evidenceRepository.findByFindingIdOrderByCreatedAtAscIdAsc(findingId)
                .stream()
                .map(this::toEvidenceDTO)
                .toList());
    }

    @GetMapping("/task/{taskId}/audit")
    @SaCheckPermission("QUERY")
    public ResponseEntity<List<ReviewAuditEventDTO>> getTaskAudit(@PathVariable Long taskId) {
        return ResponseEntity.ok(auditEventRepository.findByTaskIdOrderByIdAsc(taskId)
                .stream()
                .map(this::toAuditDTO)
                .toList());
    }

    @GetMapping("/task/{taskId}/integrity")
    @SaCheckPermission("QUERY")
    public ResponseEntity<ReviewIntegrityDTO> verifyTaskIntegrity(@PathVariable Long taskId) {
        return ResponseEntity.ok(proofBundleService.buildIntegrity(taskId));
    }

    @GetMapping("/task/{taskId}/grounding-policy")
    @SaCheckPermission("QUERY")
    public ResponseEntity<ReviewGroundingPolicyDTO> getGroundingPolicy(@PathVariable Long taskId) {
        return ResponseEntity.ok(proofBundleService.buildBundle(taskId).getGroundingPolicy());
    }

    @GetMapping("/task/{taskId}/runtime-manifest")
    @SaCheckPermission("QUERY")
    public ResponseEntity<ReviewRuntimeManifestDTO> getRuntimeManifest(@PathVariable Long taskId) {
        return ResponseEntity.ok(proofBundleService.buildBundle(taskId).getRuntimeManifest());
    }

    @GetMapping("/task/{taskId}/trace-graph")
    @SaCheckPermission("QUERY")
    public ResponseEntity<ReviewTraceGraphDTO> getTraceGraph(@PathVariable Long taskId) {
        return ResponseEntity.ok(traceGraphService.buildGraph(taskId));
    }

    @GetMapping("/task/{taskId}/assurance-summary")
    @SaCheckPermission("QUERY")
    public ResponseEntity<ReviewAssuranceSummaryDTO> getAssuranceSummary(@PathVariable Long taskId) {
        return ResponseEntity.ok(assuranceService.buildSummary(taskId));
    }

    @GetMapping("/task/{taskId}/proof-bundle")
    @SaCheckPermission("QUERY")
    public ResponseEntity<ReviewProofBundleDTO> getProofBundle(@PathVariable Long taskId) {
        return ResponseEntity.ok(proofBundleService.buildBundle(taskId));
    }

    @GetMapping("/task/{taskId}/proof-bundle/archive")
    @SaCheckPermission("QUERY")
    public ResponseEntity<byte[]> downloadProofBundleArchive(@PathVariable Long taskId) {
        ReviewProofBundleArchiveDTO archive = proofBundleArchiveService.buildArchive(taskId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + archive.getFileName() + "\"")
                .header("X-CodeGuardian-Proof-Bundle-Hash", nullToEmpty(archive.getBundleHash()))
                .header("X-CodeGuardian-Review-State-Hash", nullToEmpty(archive.getReviewStateHash()))
                .header("X-CodeGuardian-Proof-Content-Hash", nullToEmpty(archive.getContentHash()))
                .header("X-CodeGuardian-Proof-Verification", String.valueOf(archive.getVerificationValid()))
                .header("X-CodeGuardian-Proof-Verification-Reason", nullToEmpty(archive.getVerificationReason()))
                .contentType(MediaType.parseMediaType(archive.getMediaType()))
                .contentLength(archive.getContentLength())
                .body(archive.contentBytes());
    }

    @PostMapping("/proof-bundle/verify")
    @SaCheckPermission("QUERY")
    public ResponseEntity<ReviewProofBundleVerificationDTO> verifyProofBundle(@RequestBody ReviewProofBundleDTO bundle) {
        return ResponseEntity.ok(proofBundleService.verifyBundle(bundle));
    }

    @PostMapping("/task/{taskId}/proof-bundle/verify-current")
    @SaCheckPermission("QUERY")
    public ResponseEntity<ReviewProofBundleVerificationDTO> verifyProofBundleAgainstCurrentState(
            @PathVariable Long taskId,
            @RequestBody ReviewProofBundleDTO bundle) {
        return ResponseEntity.ok(proofBundleService.verifyBundleAgainstCurrentState(taskId, bundle));
    }

    private ReviewEvidenceDTO toEvidenceDTO(ReviewEvidence evidence) {
        return ReviewEvidenceDTO.builder()
                .id(evidence.getId())
                .taskId(evidence.getTaskId())
                .findingId(evidence.getFindingId())
                .evidenceType(evidence.getEvidenceType())
                .sourceName(evidence.getSourceName())
                .sourceRef(evidence.getSourceRef())
                .locator(evidence.getLocator())
                .startLine(evidence.getStartLine())
                .endLine(evidence.getEndLine())
                .excerpt(evidence.getExcerpt())
                .contentHash(evidence.getContentHash())
                .relevanceScore(evidence.getRelevanceScore())
                .metadata(evidence.getMetadata())
                .createdAt(evidence.getCreatedAt())
                .build();
    }

    private ReviewAuditEventDTO toAuditDTO(ReviewAuditEvent event) {
        return ReviewAuditEventDTO.builder()
                .id(event.getId())
                .taskId(event.getTaskId())
                .eventType(event.getEventType())
                .stage(event.getStage())
                .actor(event.getActor())
                .message(event.getMessage())
                .payloadHash(event.getPayloadHash())
                .previousHash(event.getPreviousHash())
                .eventHash(event.getEventHash())
                .signatureKeyId(event.getSignatureKeyId())
                .signatureAlgorithm(event.getSignatureAlgorithm())
                .eventSignature(event.getEventSignature())
                .metadata(event.getMetadata())
                .createdAt(event.getCreatedAt())
                .build();
    }

    private String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
