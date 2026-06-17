package com.codeguardian.service.agent;

import com.codeguardian.dto.ReviewRequestDTO;
import com.codeguardian.entity.Finding;
import com.codeguardian.service.ai.context.ReviewContextHolder;
import com.codeguardian.service.rag.RetrievedKnowledgeChunk;
import com.codeguardian.service.rules.RuleEngineService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewAgentOrchestrator {

    private static final int RAG_MAX_ITERATIONS = 2;
    private static final int JUDGE_MAX_ITERATIONS = 2;
    private static final int DEBATE_MAX_ITERATIONS = 3;

    private final RuleEngineService ruleEngineService;
    private final ReviewAgentPlanService planService;
    private final ReviewAgentRagService ragService;
    private final ReviewerAgentService reviewerAgentService;
    private final JudgeAgentService judgeAgentService;
    private final AgentFindingLifecycleService lifecycleService;
    private final DynamicDebateLoopService dynamicDebateLoopService;
    private final ReviewAgentVerifier verifier;
    private final ReviewAgentAuditService auditService;
    private final ObjectMapper objectMapper;

    public List<Finding> execute(String codeContent,
                                 String language,
                                 ReviewRequestDTO request,
                                 Long taskId,
                                 String sourceRef) {
        return executeWithEvidence(codeContent, language, request, taskId, sourceRef).getFindings();
    }

    public AgentReviewResult executeWithEvidence(String codeContent,
                                                 String language,
                                                 ReviewRequestDTO request,
                                                 Long taskId,
                                                 String sourceRef) {
        ReviewContextHolder.clear();
        ReviewAgentState state = ReviewAgentState.builder()
                .workflowRunId(UUID.randomUUID().toString())
                .taskId(taskId)
                .sourceRef(sourceRef != null ? sourceRef : "code-snippet")
                .language(language != null ? language : "Unknown")
                .code(codeContent != null ? codeContent : "")
                .request(request)
                .seedFindings(List.of())
                .ragChunks(List.of())
                .draftFindings(List.of())
                .lifecycleRecords(List.of())
                .judgeDecisions(List.of())
                .finalFindings(List.of())
                .build();
        try {
            auditService.record(
                    state,
                    ReviewAgentWorkflow.AGENT_WORKFLOW_STARTED,
                    "ORCHESTRATOR",
                    1,
                    1,
                    "Controlled dual-role review agent workflow started",
                    auditService.meta(
                            "agentMode", true,
                            "reviewerRoles", request.getReviewerRoles(),
                            "enableRag", request.getEnableRag(),
                            "modelProvider", request.getModelProvider()
                    )
            );

            List<Finding> seeds = staticScan(state);
            state.setSeedFindings(seeds);

            String planMarkdown = planService.buildChineseMarkdown(state);
            state.setPlanMarkdown(planMarkdown);
            auditService.addEvidence(
                    state,
                    "AGENT_PLAN",
                    "ReviewAgentPlanService",
                    state.getSourceRef(),
                    "agent-review-plan.md",
                    planMarkdown,
                    null,
                    auditService.meta(
                            "format", "markdown",
                            "role", "PLANNER",
                            "planVersion", "agent-review-plan-v1",
                            "seedFindingCount", seeds.size(),
                            "promptBoundary", AgentRoleBoundaries.PLANNER
                    )
            );
            auditService.record(
                    state,
                    ReviewAgentWorkflow.AGENT_PLAN_CREATED,
                    "PLANNER",
                    1,
                    1,
                    "Agent Markdown review plan created",
                    auditService.meta("planHash", auditService.safeHash(planMarkdown), "seedFindingCount", seeds.size())
            );

            List<RetrievedKnowledgeChunk> ragChunks = Boolean.FALSE.equals(request.getEnableRag())
                    ? List.of()
                    : ragService.retrieveWithLoop(RagAgentContext.from(state), RAG_MAX_ITERATIONS);
            state.setRagChunks(ragChunks);
            String ragContext = ragService.toPromptContext(ragChunks);

            List<AgentReviewerRole> reviewerRoles = AgentReviewerRole.resolve(request.getReviewerRoles());
            List<AgentCandidateFinding> drafts = draftWithCouncilRoles(state, ragContext, reviewerRoles);
            drafts = dynamicDebateLoopService.run(state, drafts, DEBATE_MAX_ITERATIONS);
            state.setDraftFindings(drafts);
            List<AgentFindingLifecycleRecord> lifecycleRecords = lifecycleService.openCandidates(state, drafts);
            state.setLifecycleRecords(lifecycleRecords);
            recordJsonEvidence(state, "AGENT_REVIEWER_DRAFT", "ReviewerAgentService",
                    "reviewer-draft-findings.json", drafts, "REVIEWER");
            auditService.record(
                    state,
                    ReviewAgentWorkflow.REVIEWER_DRAFT_CREATED,
                    "REVIEWER",
                    1,
                    1,
                    "Reviewer agent draft findings created",
                    auditService.meta(
                            "draftCount", drafts.size(),
                            "reviewerRoles", reviewerRoles.stream().map(AgentReviewerRole::name).toList()
                    )
            );

            List<JudgeDecision> decisions = List.of();
            for (int iteration = 1; iteration <= JUDGE_MAX_ITERATIONS; iteration++) {
                decisions = judgeAgentService.judge(JudgeAgentContext.from(state), drafts, iteration, JUDGE_MAX_ITERATIONS);
                state.setJudgeDecisions(decisions);
                lifecycleRecords = lifecycleService.resolveWithJudge(state, lifecycleRecords, decisions);
                state.setLifecycleRecords(lifecycleRecords);
                recordJsonEvidence(state, "AGENT_FINDING_LIFECYCLE", "AgentFindingLifecycleService",
                        "agent-finding-lifecycle-" + iteration + ".json", lifecycleRecords, "LEAD_REVIEWER");
                if (iteration < JUDGE_MAX_ITERATIONS && hasRevise(decisions)) {
                    drafts = reviewerAgentService.revise(ReviewerAgentContext.from(state), drafts, decisions, ragContext, iteration + 1);
                    state.setDraftFindings(drafts);
                    lifecycleRecords = lifecycleService.openCandidates(state, drafts);
                    state.setLifecycleRecords(lifecycleRecords);
                    recordJsonEvidence(state, "AGENT_REVIEWER_DRAFT", "ReviewerAgentService",
                            "reviewer-revised-findings-" + (iteration + 1) + ".json", drafts, "REVIEWER");
                } else {
                    break;
                }
            }

            List<Finding> finalFindings = verifier.verifyAndFinalize(VerifierAgentContext.from(state), drafts, decisions);
            enrichFinalFindingsWithLifecycle(finalFindings, lifecycleRecords);
            state.setFinalFindings(finalFindings);
            recordJsonEvidence(state, "AGENT_FINAL_FINDINGS", "ReviewAgentOrchestrator",
                    "agent-final-findings.json", finalFindings, "ORCHESTRATOR");
            auditService.record(
                    state,
                    ReviewAgentWorkflow.AGENT_WORKFLOW_COMPLETED,
                    "ORCHESTRATOR",
                    1,
                    1,
                    "Controlled dual-role review agent workflow completed",
                    auditService.meta(
                            "seedFindingCount", seeds.size(),
                            "ragChunkCount", ragChunks.size(),
                            "draftFindingCount", drafts.size(),
                            "finalFindingCount", finalFindings.size()
                    )
            );
            return AgentReviewResult.of(finalFindings, ReviewContextHolder.getEvidence());
        } catch (Exception e) {
            log.error("Agent workflow failed for {}", sourceRef, e);
            auditService.record(
                    state,
                    ReviewAgentWorkflow.AGENT_WORKFLOW_FAILED,
                    "ORCHESTRATOR",
                    1,
                    1,
                    "Controlled dual-role review agent workflow failed",
                    auditService.meta("error", e.getMessage(), "errorType", e.getClass().getName())
            );
            return AgentReviewResult.of(List.of(), ReviewContextHolder.getEvidence());
        } finally {
            ReviewContextHolder.clear();
        }
    }

    private List<AgentCandidateFinding> draftWithCouncilRoles(ReviewAgentState state,
                                                              String ragContext,
                                                              List<AgentReviewerRole> reviewerRoles) {
        List<AgentReviewerRole> roles = reviewerRoles != null && !reviewerRoles.isEmpty()
                ? reviewerRoles
                : List.of(AgentReviewerRole.GENERAL);
        Map<String, AgentCandidateFinding> deduped = new LinkedHashMap<>();
        for (AgentReviewerRole role : roles) {
            List<AgentCandidateFinding> roleDrafts = reviewerAgentService.draftForRole(ReviewerAgentContext.from(state), ragContext, role);
            auditService.record(
                    state,
                    ReviewAgentWorkflow.REVIEWER_DRAFT_CREATED,
                    "REVIEWER",
                    1,
                    1,
                    "Reviewer role draft completed",
                    auditService.meta(
                            "reviewerRole", role.name(),
                            "candidateCount", roleDrafts != null ? roleDrafts.size() : 0
                    )
            );
            for (AgentCandidateFinding candidate : roleDrafts != null ? roleDrafts : List.<AgentCandidateFinding>of()) {
                if (candidate == null || candidate.getFinding() == null) {
                    continue;
                }
                deduped.putIfAbsent(candidateKey(candidate), candidate);
            }
        }
        return new ArrayList<>(deduped.values());
    }

    private String candidateKey(AgentCandidateFinding candidate) {
        var finding = candidate.getFinding();
        return safe(finding.getStartLine()) + "|"
                + safe(finding.getEndLine()) + "|"
                + safe(finding.getCategory()) + "|"
                + safe(finding.getTitle());
    }

    private String safe(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    private void enrichFinalFindingsWithLifecycle(List<Finding> finalFindings,
                                                  List<AgentFindingLifecycleRecord> lifecycleRecords) {
        if (finalFindings == null || finalFindings.isEmpty() || lifecycleRecords == null || lifecycleRecords.isEmpty()) {
            return;
        }
        Map<String, AgentFindingLifecycleRecord> recordsByFinding = new LinkedHashMap<>();
        for (AgentFindingLifecycleRecord record : lifecycleRecords) {
            if (record == null || record.getFinding() == null) {
                continue;
            }
            if (record.getStatus() == AgentFindingStatus.ACCEPTED || record.getStatus() == AgentFindingStatus.DOWNGRADED) {
                recordsByFinding.putIfAbsent(findingKey(record.getFinding()), record);
            }
        }
        for (Finding finding : finalFindings) {
            AgentFindingLifecycleRecord record = recordsByFinding.get(findingKey(finding));
            if (record == null) {
                continue;
            }
            String summary = "Agent lifecycle: findingId=" + record.getFindingId()
                    + ", status=" + record.getStatus()
                    + ", proposedBy=" + record.getProposedByRole()
                    + ", resolvedBy=" + record.getResolvedByRole()
                    + ", reason=" + safe(record.getResolutionReason());
            finding.setGroundingSummary(appendSummary(finding.getGroundingSummary(), summary));
        }
    }

    private String findingKey(Finding finding) {
        return safe(finding.getStartLine()) + "|"
                + safe(finding.getEndLine()) + "|"
                + safe(finding.getCategory()) + "|"
                + safe(finding.getTitle());
    }

    private String appendSummary(String existing, String addition) {
        if (existing == null || existing.isBlank()) {
            return addition;
        }
        return existing + "\n" + addition;
    }

    private List<Finding> staticScan(ReviewAgentState state) {
        try {
            ReviewRequestDTO request = state.getRequest();
            List<Finding> findings;
            if ("CUSTOM".equalsIgnoreCase(request.getRuleTemplate())) {
                findings = ruleEngineService.reviewWithCustom(state.getCode(), request.getCustomRules());
            } else {
                findings = ruleEngineService.reviewWithTemplate(state.getCode(), state.getLanguage(), request.getRuleTemplate());
            }
            List<Finding> seeds = findings != null ? new ArrayList<>(findings) : List.of();
            seeds.forEach(finding -> finding.setSource("RuleEngine"));
            recordJsonEvidence(state, "AGENT_STATIC_SCAN", "RuleEngineService",
                    "agent-static-scan.json", seeds, "STATIC_SCAN");
            auditService.record(
                    state,
                    ReviewAgentWorkflow.STATIC_SCAN_COMPLETED,
                    "STATIC_SCAN",
                    1,
                    1,
                    "Agent static scan completed",
                    auditService.meta("seedFindingCount", seeds.size())
            );
            return seeds;
        } catch (Exception e) {
            auditService.record(
                    state,
                    ReviewAgentWorkflow.STATIC_SCAN_COMPLETED,
                    "STATIC_SCAN",
                    1,
                    1,
                    "Agent static scan failed; continuing without seed findings",
                    auditService.meta("error", e.getMessage(), "errorType", e.getClass().getName())
            );
            return List.of();
        }
    }

    private boolean hasRevise(List<JudgeDecision> decisions) {
        return decisions != null && decisions.stream()
                .anyMatch(decision -> decision != null && "REVISE".equalsIgnoreCase(decision.getDecision()));
    }

    private void recordJsonEvidence(ReviewAgentState state,
                                    String evidenceType,
                                    String sourceName,
                                    String locator,
                                    Object payload,
                                    String role) {
        String json = serialize(payload);
        auditService.addEvidence(
                state,
                evidenceType,
                sourceName,
                state.getSourceRef(),
                locator,
                json,
                null,
                auditService.meta(
                        "format", "json",
                        "role", role,
                        "payloadHash", auditService.safeHash(json),
                        "promptBoundary", AgentRoleBoundaries.FINALIZER
                )
        );
    }

    private String serialize(Object payload) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        } catch (Exception e) {
            return String.valueOf(payload);
        }
    }
}
