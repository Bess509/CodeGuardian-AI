package com.codeguardian.service.provenance;

import com.codeguardian.dto.ReviewAuditEventDTO;
import com.codeguardian.dto.ReviewEvidenceDTO;
import com.codeguardian.dto.ReviewProofBundleDTO;
import com.codeguardian.entity.Finding;
import com.codeguardian.entity.ReviewAuditEvent;
import com.codeguardian.entity.ReviewEvidence;
import com.codeguardian.entity.ReviewTask;
import com.codeguardian.enums.ReviewTypeEnum;
import com.codeguardian.enums.SeverityEnum;
import com.codeguardian.enums.TaskStatusEnum;

final class ReviewProofBundleMapper {

    private final ProvenanceHashService hashService;

    ReviewProofBundleMapper(ProvenanceHashService hashService) {
        this.hashService = hashService;
    }

    ReviewProofBundleDTO.TaskSnapshot toTaskSnapshot(ReviewTask task) {
        ReviewTypeEnum reviewType = ReviewTypeEnum.fromValue(task.getReviewType());
        TaskStatusEnum status = TaskStatusEnum.fromValue(task.getStatus());
        return ReviewProofBundleDTO.TaskSnapshot.builder()
                .id(task.getId())
                .name(task.getName())
                .reviewType(task.getReviewType())
                .reviewTypeLabel(reviewType.getDesc())
                .scope(task.getScope())
                .scopeHash(hashService.sha256Hex(task.getScope()))
                .status(task.getStatus())
                .statusLabel(status.getDesc())
                .createdAt(task.getCreatedAt())
                .completedAt(task.getCompletedAt())
                .errorMessage(task.getErrorMessage())
                .build();
    }

    ReviewProofBundleDTO.FindingSnapshot toFindingSnapshot(Finding finding) {
        SeverityEnum severity = SeverityEnum.fromValue(finding.getSeverity());
        return ReviewProofBundleDTO.FindingSnapshot.builder()
                .id(finding.getId())
                .taskId(finding.getTaskId())
                .severity(finding.getSeverity())
                .severityLabel(severity.getDesc())
                .title(finding.getTitle())
                .location(finding.getLocation())
                .startLine(finding.getStartLine())
                .endLine(finding.getEndLine())
                .description(finding.getDescription())
                .suggestion(finding.getSuggestion())
                .diff(finding.getDiff())
                .category(finding.getCategory())
                .source(finding.getSource())
                .confidence(finding.getConfidence())
                .grounded(finding.getGrounded())
                .evidenceCount(finding.getEvidenceCount())
                .evidenceHash(finding.getEvidenceHash())
                .groundingSummary(finding.getGroundingSummary())
                .createdAt(finding.getCreatedAt())
                .build();
    }

    ReviewEvidenceDTO toEvidenceDTO(ReviewEvidence evidence) {
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

    ReviewAuditEventDTO toAuditDTO(ReviewAuditEvent event) {
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
}
