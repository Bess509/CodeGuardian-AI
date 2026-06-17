package com.codeguardian.service.agent;

import com.codeguardian.entity.Finding;
import com.codeguardian.enums.SeverityEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReviewAgentVerifier {

    private final ObjectMapper objectMapper;
    private final ReviewAgentAuditService auditService;

    public List<Finding> verifyAndFinalize(VerifierAgentContext context,
                                           List<AgentCandidateFinding> candidates,
                                           List<JudgeDecision> decisions) {
        Map<String, JudgeDecision> decisionByDraftId = decisions != null
                ? decisions.stream()
                .filter(Objects::nonNull)
                .filter(decision -> decision.getDraftId() != null)
                .collect(Collectors.toMap(JudgeDecision::getDraftId, d -> d, (a, b) -> a, LinkedHashMap::new))
                : Map.of();
        List<Finding> accepted = new ArrayList<>();
        List<Map<String, Object>> rejected = new ArrayList<>();
        Set<String> seen = new java.util.LinkedHashSet<>();
        for (AgentCandidateFinding candidate : candidates != null ? candidates : List.<AgentCandidateFinding>of()) {
            Finding finding = candidate.getFinding();
            JudgeDecision decision = decisionByDraftId.get(candidate.getDraftId());
            String action = decision != null && decision.getDecision() != null ? decision.getDecision() : "DROP";
            if ("DROP".equalsIgnoreCase(action)) {
                rejected.add(rejection(candidate, "judge_drop"));
                continue;
            }
            String validationError = validateSourceAnchor(context, finding);
            if (validationError != null) {
                rejected.add(rejection(candidate, validationError));
                continue;
            }
            normalizeFinding(finding, context, decision);
            String key = finding.getStartLine() + "|" + safe(finding.getCategory()) + "|" + safe(finding.getTitle());
            if (!seen.add(key)) {
                rejected.add(rejection(candidate, "duplicate_candidate"));
                continue;
            }
            AgentTextSanitizer.sanitizeFinding(finding);
            accepted.add(finding);
        }
        accepted.sort(Comparator
                .comparing((Finding finding) -> finding.getSeverity() != null ? finding.getSeverity() : Integer.MAX_VALUE)
                .thenComparing(finding -> finding.getStartLine() != null ? finding.getStartLine() : Integer.MAX_VALUE));
        auditService.addEvidence(
                context,
                "AGENT_VERIFICATION",
                "ReviewAgentVerifier",
                context.getSourceRef(),
                "deterministic-verification",
                serialize(auditService.meta(
                        "acceptedCount", accepted.size(),
                        "rejectedCount", rejected.size(),
                        "rejections", rejected
                )),
                null,
                auditService.meta(
                        "role", "DETERMINISTIC_VERIFIER",
                        "acceptedCount", accepted.size(),
                        "rejectedCount", rejected.size(),
                        "promptBoundary", AgentRoleBoundaries.FINALIZER
                )
        );
        auditService.record(
                context,
                ReviewAgentWorkflow.DETERMINISTIC_VERIFICATION_COMPLETED,
                "DETERMINISTIC_VERIFIER",
                1,
                1,
                "Deterministic agent verification completed",
                auditService.meta("acceptedCount", accepted.size(), "rejectedCount", rejected.size())
        );
        return accepted;
    }

    private void normalizeFinding(Finding finding, VerifierAgentContext context, JudgeDecision decision) {
        if (decision != null && decision.getSuggestedSeverity() != null) {
            finding.setSeverity(SeverityEnum.fromName(decision.getSuggestedSeverity()).getValue());
        }
        if (finding.getEndLine() == null || finding.getEndLine() < finding.getStartLine()) {
            finding.setEndLine(finding.getStartLine());
        }
        if (finding.getLocation() == null || finding.getLocation().isBlank()) {
            finding.setLocation(context.getSourceRef() + ":" + finding.getStartLine());
        }
        if (finding.getCategory() == null || finding.getCategory().isBlank()) {
            finding.setCategory("BUG");
        }
        finding.setSource("ReviewerAgent+JudgeAgent");
        if (finding.getConfidence() == null) {
            finding.setConfidence(0.80d);
        }
        AgentTextSanitizer.sanitizeFinding(finding);
    }

    private String validateSourceAnchor(VerifierAgentContext context, Finding finding) {
        if (finding == null) {
            return "finding_missing";
        }
        if (finding.getStartLine() == null || finding.getStartLine() <= 0) {
            return "start_line_missing";
        }
        if (finding.getEndLine() != null && finding.getEndLine() < finding.getStartLine()) {
            return "end_line_before_start_line";
        }
        int lineCount = context.lineCount();
        if (lineCount > 0 && finding.getStartLine() > lineCount) {
            return "start_line_out_of_range";
        }
        SeverityEnum severity = SeverityEnum.fromValue(finding.getSeverity());
        if ((severity == SeverityEnum.CRITICAL || severity == SeverityEnum.HIGH)
                && (finding.getDescription() == null || finding.getDescription().isBlank())) {
            return "high_risk_description_missing";
        }
        return null;
    }

    private Map<String, Object> rejection(AgentCandidateFinding candidate, String reason) {
        Map<String, Object> rejected = new LinkedHashMap<>();
        rejected.put("draftId", candidate != null ? candidate.getDraftId() : null);
        rejected.put("reason", reason);
        if (candidate != null && candidate.getFinding() != null) {
            rejected.put("title", candidate.getFinding().getTitle());
            rejected.put("startLine", candidate.getFinding().getStartLine());
        }
        return rejected;
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String safe(String value) {
        return value != null ? value : "";
    }
}
