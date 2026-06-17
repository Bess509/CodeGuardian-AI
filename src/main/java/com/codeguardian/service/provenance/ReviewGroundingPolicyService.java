package com.codeguardian.service.provenance;

import com.codeguardian.dto.ReviewEvidenceDTO;
import com.codeguardian.dto.ReviewGroundingPolicyDTO;
import com.codeguardian.dto.ReviewProofBundleDTO;
import com.codeguardian.entity.Finding;
import com.codeguardian.entity.ReviewEvidence;
import com.codeguardian.enums.SeverityEnum;
import com.codeguardian.repository.ReviewEvidenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReviewGroundingPolicyService {

    public static final String POLICY_VERSION = "codeguardian-grounding-policy-v2";
    public static final String MIN_REQUIRED_SEVERITY = "HIGH";

    private static final String SOURCE_CODE = "SOURCE_CODE";
    private static final List<String> REQUIRED_EVIDENCE_TYPES = List.of(SOURCE_CODE);

    private final ReviewEvidenceRepository evidenceRepository;
    private final ProvenanceHashService hashService;

    public ReviewGroundingPolicyDTO evaluateTaskPolicy(Long taskId, List<Finding> findings) {
        List<ReviewEvidence> evidence = taskId != null
                ? evidenceRepository.findByTaskIdOrderByCreatedAtAscIdAsc(taskId)
                : List.of();
        return evaluatePolicy(findings, evidence);
    }

    public ReviewGroundingPolicyDTO evaluatePolicy(List<Finding> findings, List<ReviewEvidence> evidence) {
        return evaluateViews(
                findings != null ? findings.stream().map(this::toFindingView).toList() : List.of(),
                evidence != null ? evidence.stream().map(this::toEvidenceView).toList() : List.of()
        );
    }

    public ReviewGroundingPolicyDTO evaluateBundlePolicy(List<ReviewProofBundleDTO.FindingSnapshot> findings,
                                                         List<ReviewEvidenceDTO> evidence) {
        return evaluateViews(
                findings != null ? findings.stream().map(this::toFindingView).toList() : List.of(),
                evidence != null ? evidence.stream().map(this::toEvidenceView).toList() : List.of()
        );
    }

    private ReviewGroundingPolicyDTO evaluateViews(List<FindingView> findings, List<EvidenceView> evidence) {
        List<FindingView> safeFindings = findings != null ? findings : List.of();
        List<EvidenceView> safeEvidence = evidence != null ? evidence.stream()
                .sorted(Comparator
                        .comparing(EvidenceView::createdAt, Comparator.nullsLast(LocalDateTime::compareTo))
                        .thenComparing(EvidenceView::id, Comparator.nullsLast(Long::compareTo)))
                .toList() : List.of();

        Map<Long, List<EvidenceView>> evidenceByFinding = safeEvidence.stream()
                .filter(item -> item.findingId() != null)
                .collect(Collectors.groupingBy(EvidenceView::findingId));

        List<ReviewGroundingPolicyDTO.Violation> violations = new ArrayList<>();
        int requiredCount = 0;
        int missingEvidenceCount = 0;
        int missingSourceEvidenceCount = 0;
        int evidenceCountMismatchCount = 0;
        int evidenceHashMismatchCount = 0;
        int invalidEvidenceContentHashCount = 0;
        int invalidSourceAnchorCount = 0;

        for (FindingView finding : safeFindings) {
            if (!requiresGrounding(finding.severity())) {
                continue;
            }

            requiredCount++;
            List<EvidenceView> linkedEvidence = finding.id() != null
                    ? evidenceByFinding.getOrDefault(finding.id(), List.of())
                    : List.of();
            List<String> reasons = new ArrayList<>();

            if (!Boolean.TRUE.equals(finding.grounded())) {
                reasons.add("not_grounded");
            }
            if (linkedEvidence.isEmpty()) {
                reasons.add("evidence_missing");
                missingEvidenceCount++;
            }
            boolean hasSourceEvidence = linkedEvidence.stream()
                    .anyMatch(item -> SOURCE_CODE.equals(item.evidenceType()));
            if (!hasSourceEvidence) {
                reasons.add("source_evidence_missing");
                missingSourceEvidenceCount++;
            }
            List<EvidenceView> invalidSourceEvidence = linkedEvidence.stream()
                    .filter(item -> SOURCE_CODE.equals(item.evidenceType()))
                    .filter(this::hasInvalidSourceAnchor)
                    .toList();
            boolean hasValidSourceAnchor = linkedEvidence.stream()
                    .filter(item -> SOURCE_CODE.equals(item.evidenceType()))
                    .anyMatch(item -> !hasInvalidSourceAnchor(item));
            if (hasSourceEvidence && !hasValidSourceAnchor) {
                reasons.add("source_anchor_invalid");
                invalidSourceAnchorCount++;
            }

            int expectedEvidenceCount = linkedEvidence.size();
            int providedEvidenceCount = finding.evidenceCount() != null ? finding.evidenceCount() : 0;
            if (expectedEvidenceCount != providedEvidenceCount) {
                reasons.add("evidence_count_mismatch");
                evidenceCountMismatchCount++;
            }

            long invalidLinkedEvidence = linkedEvidence.stream()
                    .filter(this::hasInvalidContentHash)
                    .count();
            if (invalidLinkedEvidence > 0) {
                reasons.add("evidence_content_hash_mismatch");
                invalidEvidenceContentHashCount++;
            }

            String expectedEvidenceHash = combinedEvidenceHash(linkedEvidence);
            String providedEvidenceHash = finding.evidenceHash();
            if (!sameHash(expectedEvidenceHash, providedEvidenceHash)) {
                reasons.add("evidence_hash_mismatch");
                evidenceHashMismatchCount++;
            }

            if (!reasons.isEmpty()) {
                Set<String> evidenceTypes = linkedEvidence.stream()
                        .map(EvidenceView::evidenceType)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                violations.add(ReviewGroundingPolicyDTO.Violation.builder()
                        .findingId(finding.id())
                        .severity(finding.severity())
                        .severityLabel(SeverityEnum.fromValue(finding.severity()).name())
                        .title(finding.title())
                        .location(finding.location())
                        .reasons(reasons)
                        .evidenceTypes(List.copyOf(evidenceTypes))
                        .expectedEvidenceCount(expectedEvidenceCount)
                        .providedEvidenceCount(providedEvidenceCount)
                        .expectedEvidenceHash(expectedEvidenceHash)
                        .providedEvidenceHash(providedEvidenceHash)
                        .invalidSourceEvidenceIds(invalidSourceEvidence.stream()
                                .map(EvidenceView::id)
                                .filter(Objects::nonNull)
                                .toList())
                        .build());
            }
        }

        boolean valid = violations.isEmpty();
        return ReviewGroundingPolicyDTO.builder()
                .policyVersion(POLICY_VERSION)
                .minSeverity(MIN_REQUIRED_SEVERITY)
                .requiredEvidenceTypes(REQUIRED_EVIDENCE_TYPES)
                .valid(valid)
                .reason(valid ? "ok" : "grounding_policy_failed")
                .totalFindingCount(safeFindings.size())
                .requiredFindingCount(requiredCount)
                .passedFindingCount(requiredCount - violations.size())
                .violationCount(violations.size())
                .missingEvidenceCount(missingEvidenceCount)
                .missingSourceEvidenceCount(missingSourceEvidenceCount)
                .evidenceCountMismatchCount(evidenceCountMismatchCount)
                .evidenceHashMismatchCount(evidenceHashMismatchCount)
                .invalidEvidenceContentHashCount(invalidEvidenceContentHashCount)
                .invalidSourceAnchorCount(invalidSourceAnchorCount)
                .violations(violations)
                .build();
    }

    private boolean requiresGrounding(Integer severity) {
        SeverityEnum severityEnum = SeverityEnum.fromValue(severity);
        return severityEnum == SeverityEnum.CRITICAL || severityEnum == SeverityEnum.HIGH;
    }

    private boolean hasInvalidContentHash(EvidenceView evidence) {
        if (evidence.contentHash() == null || evidence.contentHash().isBlank()) {
            return true;
        }
        String expected = hashService.sha256Hex(evidence.excerpt() != null ? evidence.excerpt() : "");
        return !hashService.secureEquals(expected, evidence.contentHash());
    }

    private boolean hasInvalidSourceAnchor(EvidenceView evidence) {
        if (evidence == null || !SOURCE_CODE.equals(evidence.evidenceType())) {
            return false;
        }
        if (isBlank(evidence.sourceRef()) || isBlank(evidence.locator())) {
            return true;
        }
        if (evidence.startLine() == null || evidence.startLine() <= 0) {
            return true;
        }
        if (evidence.endLine() == null || evidence.endLine() < evidence.startLine()) {
            return true;
        }
        Object sourceCodeHash = evidence.metadata() != null ? evidence.metadata().get("sourceCodeHash") : null;
        return !isSha256Hex(sourceCodeHash);
    }

    private boolean isSha256Hex(Object value) {
        if (value == null) {
            return false;
        }
        return String.valueOf(value).matches("(?i)[0-9a-f]{64}");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String combinedEvidenceHash(List<EvidenceView> evidence) {
        String combinedHash = evidence.stream()
                .map(EvidenceView::contentHash)
                .filter(hash -> hash != null && !hash.isBlank())
                .reduce("", (left, right) -> hashService.sha256Hex(left + right));
        return combinedHash.isBlank() ? null : combinedHash;
    }

    private boolean sameHash(String expected, String provided) {
        if (expected == null || expected.isBlank()) {
            return provided == null || provided.isBlank();
        }
        return hashService.secureEquals(expected, provided);
    }

    private FindingView toFindingView(Finding finding) {
        return new FindingView(
                finding.getId(),
                finding.getSeverity(),
                finding.getTitle(),
                finding.getLocation(),
                finding.getGrounded(),
                finding.getEvidenceCount(),
                finding.getEvidenceHash()
        );
    }

    private FindingView toFindingView(ReviewProofBundleDTO.FindingSnapshot finding) {
        return new FindingView(
                finding.getId(),
                finding.getSeverity(),
                finding.getTitle(),
                finding.getLocation(),
                finding.getGrounded(),
                finding.getEvidenceCount(),
                finding.getEvidenceHash()
        );
    }

    private EvidenceView toEvidenceView(ReviewEvidence evidence) {
        return new EvidenceView(
                evidence.getId(),
                evidence.getFindingId(),
                evidence.getEvidenceType(),
                evidence.getSourceRef(),
                evidence.getLocator(),
                evidence.getStartLine(),
                evidence.getEndLine(),
                evidence.getExcerpt(),
                evidence.getContentHash(),
                evidence.getMetadata(),
                evidence.getCreatedAt()
        );
    }

    private EvidenceView toEvidenceView(ReviewEvidenceDTO evidence) {
        return new EvidenceView(
                evidence.getId(),
                evidence.getFindingId(),
                evidence.getEvidenceType(),
                evidence.getSourceRef(),
                evidence.getLocator(),
                evidence.getStartLine(),
                evidence.getEndLine(),
                evidence.getExcerpt(),
                evidence.getContentHash(),
                evidence.getMetadata(),
                evidence.getCreatedAt()
        );
    }

    private record FindingView(Long id,
                               Integer severity,
                               String title,
                               String location,
                               Boolean grounded,
                               Integer evidenceCount,
                               String evidenceHash) {
    }

    private record EvidenceView(Long id,
                                Long findingId,
                                String evidenceType,
                                String sourceRef,
                                String locator,
                                Integer startLine,
                                Integer endLine,
                                String excerpt,
                                String contentHash,
                                Map<String, Object> metadata,
                                LocalDateTime createdAt) {
    }
}
