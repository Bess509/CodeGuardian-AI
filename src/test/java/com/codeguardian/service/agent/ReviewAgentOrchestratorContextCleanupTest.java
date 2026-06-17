package com.codeguardian.service.agent;

import com.codeguardian.dto.ReviewRequestDTO;
import com.codeguardian.entity.Finding;
import com.codeguardian.enums.SeverityEnum;
import com.codeguardian.service.ai.context.ReviewContextHolder;
import com.codeguardian.service.provenance.ProvenanceHashService;
import com.codeguardian.service.provenance.ReviewAuditService;
import com.codeguardian.service.rules.RuleEngineService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReviewAgentOrchestratorContextCleanupTest {

    @AfterEach
    void cleanUp() {
        ReviewContextHolder.clear();
    }

    @Test
    void execute_with_evidence_returns_snapshot_and_leaves_thread_context_empty() {
        RuleEngineService ruleEngineService = mock(RuleEngineService.class);
        ReviewAgentRagService ragService = mock(ReviewAgentRagService.class);
        ReviewerAgentService reviewerAgentService = mock(ReviewerAgentService.class);
        JudgeAgentService judgeAgentService = mock(JudgeAgentService.class);
        AgentFindingLifecycleService lifecycleService = mock(AgentFindingLifecycleService.class);
        DynamicDebateLoopService debateLoopService = mock(DynamicDebateLoopService.class);
        ReviewAgentVerifier verifier = mock(ReviewAgentVerifier.class);
        ReviewAuditService reviewAuditService = mock(ReviewAuditService.class);
        ProvenanceHashService hashService = mock(ProvenanceHashService.class);
        ReviewAgentAuditService auditService = new ReviewAgentAuditService(reviewAuditService, hashService);

        Finding draftFinding = Finding.builder()
                .severity(SeverityEnum.HIGH.getValue())
                .title("SQL injection")
                .location("A.java:1")
                .startLine(1)
                .endLine(1)
                .description("unsafe SQL")
                .category("SECURITY")
                .build();
        AgentCandidateFinding candidate = AgentCandidateFinding.builder()
                .draftId("security-draft-1")
                .proposedByRole("SECURITY")
                .finding(draftFinding)
                .build();

        when(ruleEngineService.reviewWithTemplate(anyString(), anyString(), any()))
                .thenReturn(List.of());
        when(reviewerAgentService.draftForRole(any(ReviewerAgentContext.class), anyString(), any(AgentReviewerRole.class)))
                .thenReturn(List.of(candidate));
        when(debateLoopService.run(any(ReviewAgentState.class), any(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(lifecycleService.openCandidates(any(ReviewAgentState.class), any()))
                .thenReturn(List.of());
        when(lifecycleService.resolveWithJudge(any(ReviewAgentState.class), any(), any()))
                .thenReturn(List.of());
        when(judgeAgentService.judge(any(JudgeAgentContext.class), any(), anyInt(), anyInt()))
                .thenReturn(List.of(JudgeDecision.builder()
                        .draftId("security-draft-1")
                        .decision("KEEP")
                        .suggestedSeverity("HIGH")
                        .reason("line anchored")
                        .requiredEvidenceTypes(List.of("SOURCE_CODE"))
                        .build()));
        when(verifier.verifyAndFinalize(any(VerifierAgentContext.class), any(), any()))
                .thenReturn(List.of(draftFinding));

        ReviewAgentOrchestrator orchestrator = new ReviewAgentOrchestrator(
                ruleEngineService,
                new ReviewAgentPlanService(),
                ragService,
                reviewerAgentService,
                judgeAgentService,
                lifecycleService,
                debateLoopService,
                verifier,
                auditService,
                new ObjectMapper()
        );

        AgentReviewResult result = orchestrator.executeWithEvidence(
                "class A {}",
                "Java",
                ReviewRequestDTO.builder()
                        .agentMode(true)
                        .enableRag(false)
                        .ruleTemplate("default")
                        .reviewerRoles(List.of("security"))
                        .build(),
                7L,
                "A.java"
        );

        assertThat(result.getFindings()).hasSize(1);
        assertThat(result.getEvidenceDrafts()).isNotEmpty();
        assertThat(ReviewContextHolder.getEvidence()).isEmpty();
        assertThat(ReviewContextHolder.getFindings()).isEmpty();
    }
}
