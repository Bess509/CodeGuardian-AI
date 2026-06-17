package com.codeguardian.service;

import com.codeguardian.dto.ReviewRequestDTO;
import com.codeguardian.entity.Finding;
import com.codeguardian.entity.ReviewTask;
import com.codeguardian.repository.FindingRepository;
import com.codeguardian.repository.ReviewTaskRepository;
import com.codeguardian.service.agent.ReviewAgentOrchestrator;
import com.codeguardian.service.cache.SemanticFingerprintCacheService;
import com.codeguardian.service.provenance.ReviewAuditService;
import com.codeguardian.service.provenance.ReviewProvenanceService;
import com.codeguardian.service.rag.FindingRagEvidenceService;
import com.codeguardian.service.rules.RuleEngineService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewExecutionCoordinatorPreflightTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void shutdownExecutor() {
        executor.shutdownNow();
    }

    @Test
    void should_stop_before_ai_when_snippet_does_not_compile() {
        ReviewTaskRepository taskRepository = mock(ReviewTaskRepository.class);
        FindingRepository findingRepository = mock(FindingRepository.class);
        AIModelService aiModelService = mock(AIModelService.class);
        CodeParserService codeParserService = mock(CodeParserService.class);
        RuleEngineService ruleEngineService = mock(RuleEngineService.class);
        SystemConfigService configService = mock(SystemConfigService.class);
        GitService gitService = mock(GitService.class);
        SemanticFingerprintCacheService fingerprintCacheService = mock(SemanticFingerprintCacheService.class);
        ReviewAgentOrchestrator orchestrator = mock(ReviewAgentOrchestrator.class);
        ReviewAuditService auditService = mock(ReviewAuditService.class);
        ReviewProvenanceService provenanceService = mock(ReviewProvenanceService.class);
        FindingRagEvidenceService findingRagEvidenceService = mock(FindingRagEvidenceService.class);
        when(findingRepository.save(any(Finding.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ReviewExecutionCoordinator coordinator = new ReviewExecutionCoordinator(
                taskRepository,
                findingRepository,
                aiModelService,
                codeParserService,
                ruleEngineService,
                configService,
                gitService,
                fingerprintCacheService,
                orchestrator,
                auditService,
                provenanceService,
                findingRagEvidenceService,
                new JavaReviewGateService(),
                executor
        );
        ReviewTask task = ReviewTask.builder().id(8L).build();
        ReviewRequestDTO request = ReviewRequestDTO.builder()
                .reviewType("SNIPPET")
                .language("Java")
                .codeSnippet("system.println(\"hello\");")
                .modelProvider("QWEN")
                .enableRag(true)
                .build();

        coordinator.performReview(task, request);

        verify(findingRepository).save(argThat(finding ->
                "Java compilation failed".equals(finding.getTitle())
                        && finding.getDescription().contains("system")));
        verify(aiModelService, never()).reviewCode(anyString(), anyString(), anyString(), anyBoolean(), anyString(), any());
        verify(fingerprintCacheService, never()).tryGetCachedFindings(anyString(), anyString(), any(), anyBoolean(), anyInt());
        verify(auditService).record(eq(8L), eq("REVIEW_GATE_BLOCKED"), eq("PREFLIGHT"), eq("system"), anyString(), anyMap());
    }
}
