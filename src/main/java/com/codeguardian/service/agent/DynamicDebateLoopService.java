package com.codeguardian.service.agent;

import com.codeguardian.entity.Finding;
import com.codeguardian.enums.SeverityEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DynamicDebateLoopService {

    private final ReviewAgentAuditService auditService;
    private final ObjectMapper objectMapper;

    public List<AgentCandidateFinding> run(ReviewAgentState state,
                                           List<AgentCandidateFinding> candidates,
                                           int maxIterations) {
        List<AgentCandidateFinding> current = new ArrayList<>(candidates != null ? candidates : List.of());
        List<DebateActionRecord> actions = new ArrayList<>();
        int limit = Math.max(1, maxIterations);
        for (int iteration = 1; iteration <= limit; iteration++) {
            DebateActionRecord action = selectAndApply(iteration, current);
            actions.add(action);
            auditService.record(
                    state,
                    ReviewAgentWorkflow.AGENT_DEBATE_ACTION_SELECTED,
                    "DEBATE_LOOP",
                    iteration,
                    limit,
                    "Dynamic debate loop action selected",
                    auditService.meta(
                            "action", action.getAction().name(),
                            "reason", action.getReason(),
                            "draftIds", action.getDraftIds(),
                            "beforeCount", action.getBeforeCount(),
                            "afterCount", action.getAfterCount(),
                            "terminal", action.isTerminal()
                    )
            );
            if (action.isTerminal()) {
                break;
            }
        }
        auditService.addEvidence(
                state,
                "AGENT_DEBATE_ACTIONS",
                "DynamicDebateLoopService",
                state.getSourceRef(),
                "agent-debate-actions.json",
                serialize(actions),
                null,
                auditService.meta(
                        "role", "DEBATE_LOOP",
                        "actionCount", actions.size(),
                        "promptBoundary", AgentRoleBoundaries.JUDGE
                )
        );
        auditService.record(
                state,
                ReviewAgentWorkflow.AGENT_DEBATE_LOOP_COMPLETED,
                "DEBATE_LOOP",
                actions.size(),
                limit,
                "Dynamic debate loop completed",
                auditService.meta("candidateCount", current.size(), "actionCount", actions.size())
        );
        return current;
    }

    private DebateActionRecord selectAndApply(int iteration, List<AgentCandidateFinding> current) {
        int before = current.size();
        List<String> duplicateIds = duplicateDraftIds(current);
        if (!duplicateIds.isEmpty()) {
            mergeDuplicates(current);
            return record(iteration, DebateActionType.MERGE_DUPLICATES,
                    "Duplicate candidate findings share the same location/category/title.", duplicateIds, before, current.size(), false);
        }

        List<String> weakLocatorIds = current.stream()
                .filter(candidate -> candidate.getFinding() == null
                        || candidate.getFinding().getStartLine() == null
                        || candidate.getFinding().getStartLine() <= 0)
                .map(AgentCandidateFinding::getDraftId)
                .toList();
        if (!weakLocatorIds.isEmpty()) {
            current.removeIf(candidate -> weakLocatorIds.contains(candidate.getDraftId()));
            return record(iteration, DebateActionType.ASK_CRITIC,
                    "Critic rejected candidates without actionable source line anchors.", weakLocatorIds, before, current.size(), false);
        }

        List<String> missingEvidenceIds = current.stream()
                .filter(this::isHighRiskWithoutEvidence)
                .map(AgentCandidateFinding::getDraftId)
                .toList();
        if (!missingEvidenceIds.isEmpty()) {
            current.stream()
                    .filter(candidate -> missingEvidenceIds.contains(candidate.getDraftId()))
                    .forEach(candidate -> {
                        Finding finding = candidate.getFinding();
                        finding.setGroundingSummary(append(finding.getGroundingSummary(),
                                "Debate loop requested more evidence before finalization."));
                    });
            return record(iteration, DebateActionType.REQUEST_MORE_EVIDENCE,
                    "High-risk candidates need explicit source/RAG evidence before finalization.", missingEvidenceIds, before, current.size(), false);
        }

        return record(iteration, DebateActionType.FINALIZE,
                "No duplicate, weak-locator, or high-risk evidence issues remain.", List.of(), before, current.size(), true);
    }

    private boolean isHighRiskWithoutEvidence(AgentCandidateFinding candidate) {
        if (candidate == null || candidate.getFinding() == null) {
            return false;
        }
        SeverityEnum severity = SeverityEnum.fromValue(candidate.getFinding().getSeverity());
        boolean highRisk = severity == SeverityEnum.CRITICAL || severity == SeverityEnum.HIGH;
        boolean hasEvidence = candidate.getRagEvidenceRefs() != null && !candidate.getRagEvidenceRefs().isEmpty();
        boolean alreadyRequested = candidate.getFinding().getGroundingSummary() != null
                && candidate.getFinding().getGroundingSummary().contains("Debate loop requested more evidence");
        return highRisk && !hasEvidence && !alreadyRequested;
    }

    private List<String> duplicateDraftIds(List<AgentCandidateFinding> candidates) {
        Map<String, String> seen = new LinkedHashMap<>();
        List<String> duplicates = new ArrayList<>();
        for (AgentCandidateFinding candidate : candidates) {
            String key = key(candidate);
            if (seen.containsKey(key)) {
                duplicates.add(candidate.getDraftId());
            } else {
                seen.put(key, candidate.getDraftId());
            }
        }
        return duplicates;
    }

    private void mergeDuplicates(List<AgentCandidateFinding> candidates) {
        Map<String, AgentCandidateFinding> deduped = new LinkedHashMap<>();
        for (AgentCandidateFinding candidate : candidates) {
            deduped.putIfAbsent(key(candidate), candidate);
        }
        candidates.clear();
        candidates.addAll(deduped.values());
    }

    private String key(AgentCandidateFinding candidate) {
        Finding finding = candidate != null ? candidate.getFinding() : null;
        if (finding == null) {
            return "";
        }
        return safe(finding.getLocation()) + "|"
                + safe(finding.getStartLine()) + "|"
                + safe(finding.getEndLine()) + "|"
                + safe(finding.getCategory()) + "|"
                + safe(finding.getTitle());
    }

    private DebateActionRecord record(int iteration,
                                      DebateActionType action,
                                      String reason,
                                      List<String> draftIds,
                                      int before,
                                      int after,
                                      boolean terminal) {
        return DebateActionRecord.builder()
                .iteration(iteration)
                .action(action)
                .reason(reason)
                .draftIds(draftIds)
                .beforeCount(before)
                .afterCount(after)
                .terminal(terminal)
                .build();
    }

    private String append(String existing, String addition) {
        if (existing == null || existing.isBlank()) {
            return addition;
        }
        return existing + "\n" + addition;
    }

    private String safe(Object value) {
        return value != null ? String.valueOf(value).trim().toLowerCase() : "";
    }

    private String serialize(Object payload) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (Exception e) {
            return String.valueOf(payload);
        }
    }
}
