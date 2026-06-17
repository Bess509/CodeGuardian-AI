package com.codeguardian.service;

import com.codeguardian.dto.ReviewRequestDTO;
import com.codeguardian.entity.Finding;
import com.codeguardian.model.dto.SettingsDTO;
import com.codeguardian.service.agent.AgentReviewResult;
import com.codeguardian.service.agent.ReviewAgentOrchestrator;
import com.codeguardian.service.cache.SemanticFingerprintCacheService;
import com.codeguardian.service.provenance.ReviewAuditService;
import com.codeguardian.service.rules.RuleEngineService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewStrategyExecutorAgentModeTest {

    @Test
    void should_delegate_to_agent_orchestrator_when_agent_mode_enabled() {
        AIModelService aiModelService = mock(AIModelService.class);
        RuleEngineService ruleEngineService = mock(RuleEngineService.class);
        SystemConfigService configService = mock(SystemConfigService.class);
        SemanticFingerprintCacheService fingerprintCacheService = mock(SemanticFingerprintCacheService.class);
        ReviewAgentOrchestrator orchestrator = mock(ReviewAgentOrchestrator.class);
        ReviewAuditService auditService = mock(ReviewAuditService.class);

        SettingsDTO settings = new SettingsDTO();
        settings.setRuleCategories(Map.of());
        when(configService.getSettings()).thenReturn(settings);

        Finding finding = Finding.builder()
                .severity(1)
                .title("agent finding")
                .location("A.java:1")
                .startLine(1)
                .endLine(1)
                .description("d")
                .category("SECURITY")
                .build();
        when(orchestrator.executeWithEvidence(anyString(), anyString(), eq(
                ReviewRequestDTO.builder()
                        .agentMode(true)
                        .enableRag(true)
                        .rulesOnly(false)
                        .modelProvider("QWEN")
                        .build()
        ), eq(9L), eq("A.java"))).thenReturn(AgentReviewResult.of(List.of(finding), List.of()));

        ReviewStrategyExecutor executor = new ReviewStrategyExecutor(
                aiModelService,
                ruleEngineService,
                configService,
                fingerprintCacheService,
                orchestrator,
                auditService
        );
        ReviewRequestDTO request = ReviewRequestDTO.builder()
                .agentMode(true)
                .enableRag(true)
                .rulesOnly(false)
                .modelProvider("QWEN")
                .build();

        List<Finding> result = executor.execute("class A {}", "Java", request, 9L, "A.java");

        assertEquals(1, result.size());
        assertEquals("agent finding", result.get(0).getTitle());
        verify(orchestrator).executeWithEvidence("class A {}", "Java", request, 9L, "A.java");
        verify(aiModelService, never()).reviewCode(anyString(), anyString(), anyString(), eq(true), eq("A.java"), eq(List.of()));
        verify(auditService).record(eq(9L), eq("REVIEW_STRATEGY_STARTED"), eq("ANALYSIS"), eq("system"), anyString(), anyMap());
        verify(auditService).record(eq(9L), eq("REVIEW_STRATEGY_COMPLETED"), eq("ANALYSIS"), eq("system"), anyString(), anyMap());
    }
}
