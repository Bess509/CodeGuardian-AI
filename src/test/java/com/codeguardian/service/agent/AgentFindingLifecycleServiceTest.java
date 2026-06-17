package com.codeguardian.service.agent;

import com.codeguardian.entity.Finding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AgentFindingLifecycleServiceTest {

    @Test
    void should_open_candidates_with_stable_finding_ids() {
        ReviewAgentAuditService auditService = mock(ReviewAgentAuditService.class);
        AgentFindingLifecycleService service = new AgentFindingLifecycleService(auditService);
        ReviewAgentState state = ReviewAgentState.builder().taskId(1L).workflowRunId("run-1").build();

        List<AgentFindingLifecycleRecord> records = service.openCandidates(state, List.of(candidate("draft-1", 1)));

        assertEquals(1, records.size());
        assertEquals("F-001", records.get(0).getFindingId());
        assertEquals("SECURITY", records.get(0).getProposedByRole());
        assertEquals(AgentFindingStatus.CANDIDATE, records.get(0).getStatus());
        verify(auditService).record(eq(state), eq(ReviewAgentWorkflow.AGENT_FINDING_CANDIDATE_CREATED),
                eq("SECURITY"), anyInt(), anyInt(), anyString(), anyMap());
    }

    @Test
    void should_reject_drop_decisions_through_critic_and_lead() {
        ReviewAgentAuditService auditService = mock(ReviewAgentAuditService.class);
        AgentFindingLifecycleService service = new AgentFindingLifecycleService(auditService);
        ReviewAgentState state = ReviewAgentState.builder().taskId(1L).workflowRunId("run-1").build();
        List<AgentFindingLifecycleRecord> records = service.openCandidates(state, List.of(candidate("draft-1", 1)));

        service.resolveWithJudge(state, records, List.of(JudgeDecision.builder()
                .draftId("draft-1")
                .decision("DROP")
                .reason("not enough evidence")
                .build()));

        assertEquals(AgentFindingStatus.REJECTED, records.get(0).getStatus());
        assertEquals("CRITIC_REVIEWER", records.get(0).getChallengedByRole());
        assertEquals("LEAD_REVIEWER", records.get(0).getResolvedByRole());
        verify(auditService).record(eq(state), eq(ReviewAgentWorkflow.AGENT_FINDING_CHALLENGED),
                eq("CRITIC_REVIEWER"), anyInt(), anyInt(), anyString(), anyMap());
        verify(auditService).record(eq(state), eq(ReviewAgentWorkflow.AGENT_FINDING_REJECTED),
                eq("LEAD_REVIEWER"), anyInt(), anyInt(), anyString(), anyMap());
    }

    @Test
    void should_accept_keep_decisions_and_record_downgrades() {
        ReviewAgentAuditService auditService = mock(ReviewAgentAuditService.class);
        AgentFindingLifecycleService service = new AgentFindingLifecycleService(auditService);
        ReviewAgentState state = ReviewAgentState.builder().taskId(1L).workflowRunId("run-1").build();
        List<AgentFindingLifecycleRecord> records = service.openCandidates(state, List.of(candidate("draft-1", 1)));

        service.resolveWithJudge(state, records, List.of(JudgeDecision.builder()
                .draftId("draft-1")
                .decision("KEEP")
                .suggestedSeverity("LOW")
                .reason("valid but lower risk")
                .build()));

        assertEquals(AgentFindingStatus.DOWNGRADED, records.get(0).getStatus());
        assertEquals("HIGH", records.get(0).getPreviousSeverity());
        assertEquals("LOW", records.get(0).getResolvedSeverity());
        verify(auditService).record(eq(state), eq(ReviewAgentWorkflow.AGENT_FINDING_DOWNGRADED),
                eq("LEAD_REVIEWER"), anyInt(), anyInt(), anyString(), anyMap());
    }

    private AgentCandidateFinding candidate(String draftId, int line) {
        return AgentCandidateFinding.builder()
                .draftId(draftId)
                .proposedByRole("SECURITY")
                .finding(Finding.builder()
                        .title("issue " + line)
                        .severity(1)
                        .category("SECURITY")
                        .location("A.java:" + line)
                        .startLine(line)
                        .endLine(line)
                        .description("description")
                        .build())
                .ragEvidenceRefs(List.of("policy-1"))
                .build();
    }
}
