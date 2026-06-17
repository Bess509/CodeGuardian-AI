package com.codeguardian.service.provenance;

import com.codeguardian.dto.ReviewGroundingPolicyDTO;
import com.codeguardian.entity.Finding;
import com.codeguardian.entity.ReviewEvidence;
import com.codeguardian.enums.SeverityEnum;
import com.codeguardian.repository.ReviewEvidenceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ReviewGroundingPolicyServiceTest {

    private final ProvenanceHashService hashService = new ProvenanceHashService(
            new ObjectMapper().findAndRegisterModules()
    );
    private final ReviewGroundingPolicyService service = new ReviewGroundingPolicyService(
            mock(ReviewEvidenceRepository.class),
            hashService
    );

    @Test
    void should_pass_when_high_risk_finding_has_source_evidence_and_matching_hashes() {
        String excerpt = "12: String sql = query;";
        String contentHash = hashService.sha256Hex(excerpt);
        String findingEvidenceHash = hashService.sha256Hex(contentHash);
        Finding finding = Finding.builder()
                .id(10L)
                .severity(SeverityEnum.HIGH.getValue())
                .title("SQL injection")
                .location("PaymentService.java:12")
                .grounded(true)
                .evidenceCount(1)
                .evidenceHash(findingEvidenceHash)
                .build();
        ReviewEvidence evidence = ReviewEvidence.builder()
                .id(100L)
                .findingId(10L)
                .evidenceType("SOURCE_CODE")
                .sourceRef("repo://payment/PaymentService.java")
                .locator("PaymentService.java:12")
                .startLine(12)
                .endLine(12)
                .excerpt(excerpt)
                .contentHash(contentHash)
                .metadata(Map.of("sourceCodeHash", hashService.sha256Hex("class PaymentService {}")))
                .createdAt(LocalDateTime.of(2026, 6, 9, 10, 0))
                .build();

        ReviewGroundingPolicyDTO policy = service.evaluatePolicy(List.of(finding), List.of(evidence));

        assertTrue(Boolean.TRUE.equals(policy.getValid()));
        assertEquals(1, policy.getRequiredFindingCount());
        assertEquals(1, policy.getPassedFindingCount());
        assertEquals(0, policy.getViolationCount());
        assertEquals(0, policy.getInvalidSourceAnchorCount());
    }

    @Test
    void should_fail_when_high_risk_finding_has_only_context_evidence() {
        String excerpt = "Use prepared statements.";
        String contentHash = hashService.sha256Hex(excerpt);
        String findingEvidenceHash = hashService.sha256Hex(contentHash);
        Finding finding = Finding.builder()
                .id(11L)
                .severity(SeverityEnum.CRITICAL.getValue())
                .title("SQL injection")
                .location("PaymentService.java:12")
                .grounded(true)
                .evidenceCount(1)
                .evidenceHash(findingEvidenceHash)
                .build();
        ReviewEvidence evidence = ReviewEvidence.builder()
                .id(101L)
                .findingId(11L)
                .evidenceType("RAG_SNIPPET")
                .excerpt(excerpt)
                .contentHash(contentHash)
                .createdAt(LocalDateTime.of(2026, 6, 9, 10, 1))
                .build();

        ReviewGroundingPolicyDTO policy = service.evaluatePolicy(List.of(finding), List.of(evidence));

        assertFalse(Boolean.TRUE.equals(policy.getValid()));
        assertEquals("grounding_policy_failed", policy.getReason());
        assertEquals(1, policy.getMissingSourceEvidenceCount());
        assertEquals(List.of("source_evidence_missing"), policy.getViolations().get(0).getReasons());
    }

    @Test
    void should_fail_when_source_evidence_has_no_verifiable_anchor() {
        String excerpt = "12: String sql = query;";
        String contentHash = hashService.sha256Hex(excerpt);
        String findingEvidenceHash = hashService.sha256Hex(contentHash);
        Finding finding = Finding.builder()
                .id(13L)
                .severity(SeverityEnum.HIGH.getValue())
                .title("SQL injection")
                .location("PaymentService.java:12")
                .grounded(true)
                .evidenceCount(1)
                .evidenceHash(findingEvidenceHash)
                .build();
        ReviewEvidence evidence = ReviewEvidence.builder()
                .id(103L)
                .findingId(13L)
                .evidenceType("SOURCE_CODE")
                .excerpt(excerpt)
                .contentHash(contentHash)
                .createdAt(LocalDateTime.of(2026, 6, 9, 10, 2))
                .build();

        ReviewGroundingPolicyDTO policy = service.evaluatePolicy(List.of(finding), List.of(evidence));

        assertFalse(Boolean.TRUE.equals(policy.getValid()));
        assertEquals(1, policy.getInvalidSourceAnchorCount());
        assertTrue(policy.getViolations().get(0).getReasons().contains("source_anchor_invalid"));
        assertEquals(List.of(103L), policy.getViolations().get(0).getInvalidSourceEvidenceIds());
    }

    @Test
    void should_not_require_source_evidence_for_medium_findings() {
        Finding finding = Finding.builder()
                .id(12L)
                .severity(SeverityEnum.MEDIUM.getValue())
                .title("Style issue")
                .location("PaymentService.java:20")
                .grounded(false)
                .evidenceCount(0)
                .build();

        ReviewGroundingPolicyDTO policy = service.evaluatePolicy(List.of(finding), List.of());

        assertTrue(Boolean.TRUE.equals(policy.getValid()));
        assertEquals(0, policy.getRequiredFindingCount());
        assertEquals(0, policy.getViolationCount());
    }
}
