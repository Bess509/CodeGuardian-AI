package com.codeguardian.service.agent;

import com.codeguardian.entity.Finding;
import com.codeguardian.enums.SeverityEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AgentFindingLifecycleService {

    private static final String CRITIC_ROLE = "CRITIC_REVIEWER";
    private static final String LEAD_ROLE = "LEAD_REVIEWER";

    private final ReviewAgentAuditService auditService;

    public List<AgentFindingLifecycleRecord> openCandidates(ReviewAgentState state,
                                                            List<AgentCandidateFinding> candidates) {
        List<AgentFindingLifecycleRecord> records = new ArrayList<>();
        int nextId = 1;
        for (AgentCandidateFinding candidate : candidates != null ? candidates : List.<AgentCandidateFinding>of()) {
            if (candidate == null || candidate.getFinding() == null) {
                continue;
            }
            AgentFindingLifecycleRecord record = AgentFindingLifecycleRecord.builder()
                    .findingId("F-" + String.format("%03d", nextId++))
                    .draftId(candidate.getDraftId())
                    .proposedByRole(nonBlank(candidate.getProposedByRole(), AgentReviewerRole.GENERAL.name()))
                    .status(AgentFindingStatus.CANDIDATE)
                    .evidenceRefs(candidate.getRagEvidenceRefs() != null ? candidate.getRagEvidenceRefs() : List.of())
                    .finding(candidate.getFinding())
                    .build();
            records.add(record);
            auditService.record(
                    state,
                    ReviewAgentWorkflow.AGENT_FINDING_CANDIDATE_CREATED,
                    record.getProposedByRole(),
                    1,
                    1,
                    "Agent finding candidate created",
                    auditService.meta(
                            "findingId", record.getFindingId(),
                            "draftId", record.getDraftId(),
                            "proposedByRole", record.getProposedByRole(),
                            "title", record.getFinding().getTitle(),
                            "startLine", record.getFinding().getStartLine(),
                            "category", record.getFinding().getCategory(),
                            "severity", record.getFinding().getSeverity()
                    )
            );
        }
        return records;
    }

    public List<AgentFindingLifecycleRecord> resolveWithJudge(ReviewAgentState state,
                                                              List<AgentFindingLifecycleRecord> records,
                                                              List<JudgeDecision> decisions) {
        Map<String, JudgeDecision> decisionsByDraft = new LinkedHashMap<>();
        for (JudgeDecision decision : decisions != null ? decisions : List.<JudgeDecision>of()) {
            if (decision != null && decision.getDraftId() != null) {
                decisionsByDraft.put(decision.getDraftId(), decision);
            }
        }
        for (AgentFindingLifecycleRecord record : records != null ? records : List.<AgentFindingLifecycleRecord>of()) {
            JudgeDecision decision = decisionsByDraft.get(record.getDraftId());
            String action = decision != null && decision.getDecision() != null
                    ? decision.getDecision().trim().toUpperCase(java.util.Locale.ROOT)
                    : "DROP";
            if ("DROP".equals(action)) {
                challenge(state, record, decision, "Judge requested drop or evidence was insufficient");
                reject(state, record, decision, reason(decision, "Rejected by lead reviewer after critic challenge"));
            } else if ("REVISE".equals(action)) {
                challenge(state, record, decision, "Judge requested revision before acceptance");
            } else {
                resolveAcceptedOrDowngraded(state, record, decision);
            }
        }
        return records != null ? records : List.of();
    }

    private void resolveAcceptedOrDowngraded(ReviewAgentState state,
                                             AgentFindingLifecycleRecord record,
                                             JudgeDecision decision) {
        String currentSeverity = severityName(record.getFinding());
        String suggestedSeverity = decision != null ? decision.getSuggestedSeverity() : null;
        SeverityEnum current = SeverityEnum.fromName(currentSeverity);
        SeverityEnum suggested = suggestedSeverity != null && !suggestedSeverity.isBlank()
                ? SeverityEnum.fromName(suggestedSeverity)
                : current;
        boolean downgraded = suggested.getValue() > current.getValue();
        record.setPreviousSeverity(current.name());
        record.setResolvedSeverity(suggested.name());
        record.setResolvedByRole(LEAD_ROLE);
        record.setResolutionReason(reason(decision, downgraded
                ? "Lead reviewer accepted the finding with lower severity"
                : "Lead reviewer accepted the finding"));
        record.setStatus(downgraded ? AgentFindingStatus.DOWNGRADED : AgentFindingStatus.ACCEPTED);
        auditService.record(
                state,
                downgraded ? ReviewAgentWorkflow.AGENT_FINDING_DOWNGRADED : ReviewAgentWorkflow.AGENT_FINDING_ACCEPTED,
                LEAD_ROLE,
                1,
                1,
                downgraded ? "Agent finding downgraded by lead reviewer" : "Agent finding accepted by lead reviewer",
                auditService.meta(
                        "findingId", record.getFindingId(),
                        "draftId", record.getDraftId(),
                        "previousSeverity", record.getPreviousSeverity(),
                        "resolvedSeverity", record.getResolvedSeverity(),
                        "reason", record.getResolutionReason()
                )
        );
    }

    private void challenge(ReviewAgentState state,
                           AgentFindingLifecycleRecord record,
                           JudgeDecision decision,
                           String fallbackReason) {
        record.setStatus(AgentFindingStatus.CHALLENGED);
        record.setChallengedByRole(CRITIC_ROLE);
        record.setResolutionReason(reason(decision, fallbackReason));
        auditService.record(
                state,
                ReviewAgentWorkflow.AGENT_FINDING_CHALLENGED,
                CRITIC_ROLE,
                1,
                1,
                "Agent finding challenged by critic reviewer",
                auditService.meta(
                        "findingId", record.getFindingId(),
                        "draftId", record.getDraftId(),
                        "reason", record.getResolutionReason(),
                        "requiredEvidenceTypes", decision != null ? decision.getRequiredEvidenceTypes() : null
                )
        );
    }

    private void reject(ReviewAgentState state,
                        AgentFindingLifecycleRecord record,
                        JudgeDecision decision,
                        String fallbackReason) {
        record.setStatus(AgentFindingStatus.REJECTED);
        record.setResolvedByRole(LEAD_ROLE);
        record.setResolutionReason(reason(decision, fallbackReason));
        auditService.record(
                state,
                ReviewAgentWorkflow.AGENT_FINDING_REJECTED,
                LEAD_ROLE,
                1,
                1,
                "Agent finding rejected by lead reviewer",
                auditService.meta(
                        "findingId", record.getFindingId(),
                        "draftId", record.getDraftId(),
                        "reason", record.getResolutionReason()
                )
        );
    }

    private String severityName(Finding finding) {
        return SeverityEnum.fromValue(finding != null ? finding.getSeverity() : null).name();
    }

    private String reason(JudgeDecision decision, String fallback) {
        return decision != null && decision.getReason() != null && !decision.getReason().isBlank()
                ? decision.getReason()
                : fallback;
    }

    private String nonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }
}
