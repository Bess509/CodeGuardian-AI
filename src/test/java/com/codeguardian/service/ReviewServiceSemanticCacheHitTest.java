package com.codeguardian.service;

import com.codeguardian.dto.ReviewRequestDTO;
import com.codeguardian.entity.Finding;
import com.codeguardian.enums.ModelProviderEnum;
import com.codeguardian.model.dto.SettingsDTO;
import com.codeguardian.repository.FindingRepository;
import com.codeguardian.repository.ReviewTaskRepository;
import com.codeguardian.service.cache.SemanticFingerprintCacheService;
import com.codeguardian.service.ai.context.ReviewContextHolder;
import com.codeguardian.service.provenance.EvidenceDraft;
import com.codeguardian.service.provenance.ReviewAuditService;
import com.codeguardian.service.provenance.ReviewProvenanceService;
import com.codeguardian.service.rules.RuleEngineService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ReviewServiceSemanticCacheHitTest {

    @AfterEach
    void clearReviewContext() {
        ReviewContextHolder.clear();
    }

    @Test
    void should_not_call_ai_when_cache_hit() throws Exception {
        ReviewTaskRepository taskRepository = mock(ReviewTaskRepository.class);
        FindingRepository findingRepository = mock(FindingRepository.class);
        AIModelService aiModelService = mock(AIModelService.class);
        CodeParserService codeParserService = mock(CodeParserService.class);
        RuleEngineService ruleEngineService = mock(RuleEngineService.class);
        SystemConfigService configService = mock(SystemConfigService.class);
        GitService gitService = mock(GitService.class);
        SemanticFingerprintCacheService fingerprintCacheService = mock(SemanticFingerprintCacheService.class);
        ReviewAuditService auditService = mock(ReviewAuditService.class);
        ReviewProvenanceService provenanceService = mock(ReviewProvenanceService.class);

        SettingsDTO settings = new SettingsDTO();
        settings.setRuleCategories(java.util.Collections.emptyMap());
        when(configService.getSettings()).thenReturn(settings);

        Finding cachedFinding = new Finding();
        cachedFinding.setSeverity(1);
        cachedFinding.setTitle("cached");
        cachedFinding.setLocation("L1");
        cachedFinding.setDescription("d");

        when(fingerprintCacheService.tryGetCachedFindings(anyString(), anyString(), any(ModelProviderEnum.class), anyBoolean(), anyInt()))
                .thenReturn(Optional.of(List.of(cachedFinding)));

        ReviewService service = new ReviewService(
                taskRepository,
                findingRepository,
                aiModelService,
                codeParserService,
                ruleEngineService,
                configService,
                gitService,
                fingerprintCacheService,
                null,
                auditService,
                provenanceService
        );

        ReviewRequestDTO request = ReviewRequestDTO.builder()
                .rulesOnly(false)
                .modelProvider("QWEN")
                .enableRag(true)
                .build();

        Method method = ReviewService.class.getDeclaredMethod("executeReviewStrategy", String.class, String.class, ReviewRequestDTO.class);
        method.setAccessible(true);
        List<Finding> findings = (List<Finding>) method.invoke(service, "class A {}", "Java", request);

        assertEquals(1, findings.size());
        assertEquals("cached", findings.get(0).getTitle());
        verify(aiModelService, never()).reviewCode(anyString(), anyString(), anyString(), anyBoolean());
        verify(aiModelService, never()).reviewCode(anyString(), anyString(), anyString(), anyBoolean(), anyString(), any());
    }

    @Test
    void should_record_audit_events_when_cache_hit_has_task_context() throws Exception {
        ReviewTaskRepository taskRepository = mock(ReviewTaskRepository.class);
        FindingRepository findingRepository = mock(FindingRepository.class);
        AIModelService aiModelService = mock(AIModelService.class);
        CodeParserService codeParserService = mock(CodeParserService.class);
        RuleEngineService ruleEngineService = mock(RuleEngineService.class);
        SystemConfigService configService = mock(SystemConfigService.class);
        GitService gitService = mock(GitService.class);
        SemanticFingerprintCacheService fingerprintCacheService = mock(SemanticFingerprintCacheService.class);
        ReviewAuditService auditService = mock(ReviewAuditService.class);
        ReviewProvenanceService provenanceService = mock(ReviewProvenanceService.class);

        SettingsDTO settings = new SettingsDTO();
        settings.setRuleCategories(java.util.Collections.emptyMap());
        when(configService.getSettings()).thenReturn(settings);

        Finding cachedFinding = new Finding();
        cachedFinding.setSeverity(1);
        cachedFinding.setTitle("cached");
        cachedFinding.setLocation("L1");
        cachedFinding.setDescription("d");

        when(fingerprintCacheService.tryGetCachedFindings(anyString(), anyString(), any(ModelProviderEnum.class), anyBoolean(), anyInt()))
                .thenReturn(Optional.of(List.of(cachedFinding)));

        ReviewService service = new ReviewService(
                taskRepository,
                findingRepository,
                aiModelService,
                codeParserService,
                ruleEngineService,
                configService,
                gitService,
                fingerprintCacheService,
                null,
                auditService,
                provenanceService
        );

        ReviewRequestDTO request = ReviewRequestDTO.builder()
                .rulesOnly(false)
                .modelProvider("QWEN")
                .enableRag(true)
                .build();

        Method method = ReviewService.class.getDeclaredMethod("executeReviewStrategy",
                String.class, String.class, ReviewRequestDTO.class, Long.class, String.class);
        method.setAccessible(true);
        List<Finding> findings = (List<Finding>) method.invoke(service, "class A {}", "Java", request, 7L, "A.java");

        assertEquals(1, findings.size());
        verify(aiModelService, never()).reviewCode(anyString(), anyString(), anyString(), anyBoolean());
        verify(aiModelService, never()).reviewCode(anyString(), anyString(), anyString(), anyBoolean(), anyString(), any());
        verify(auditService).record(eq(7L), eq("REVIEW_STRATEGY_STARTED"), eq("ANALYSIS"), eq("system"), anyString(), anyMap());
        verify(auditService).record(eq(7L), eq("SEMANTIC_CACHE_HIT"), eq("CACHE"), eq("system"), anyString(), anyMap());
        verify(auditService).record(eq(7L), eq("REVIEW_STRATEGY_COMPLETED"), eq("ANALYSIS"), eq("system"), anyString(), anyMap());
    }

    @Test
    void should_merge_rule_findings_when_semantic_cache_hit_is_empty() throws Exception {
        ReviewTaskRepository taskRepository = mock(ReviewTaskRepository.class);
        FindingRepository findingRepository = mock(FindingRepository.class);
        AIModelService aiModelService = mock(AIModelService.class);
        CodeParserService codeParserService = mock(CodeParserService.class);
        RuleEngineService ruleEngineService = mock(RuleEngineService.class);
        SystemConfigService configService = mock(SystemConfigService.class);
        GitService gitService = mock(GitService.class);
        SemanticFingerprintCacheService fingerprintCacheService = mock(SemanticFingerprintCacheService.class);
        ReviewAuditService auditService = mock(ReviewAuditService.class);
        ReviewProvenanceService provenanceService = mock(ReviewProvenanceService.class);

        SettingsDTO settings = new SettingsDTO();
        settings.setRuleCategories(java.util.Collections.emptyMap());
        when(configService.getSettings()).thenReturn(settings);

        Finding ruleFinding = new Finding();
        ruleFinding.setSeverity(0);
        ruleFinding.setTitle("SQL注入风险");
        ruleFinding.setLocation("Line 8");
        ruleFinding.setStartLine(8);
        ruleFinding.setCategory("SECURITY");
        ruleFinding.setDescription("d");

        when(ruleEngineService.reviewWithTemplate(anyString(), anyString(), any()))
                .thenReturn(List.of(ruleFinding));
        when(fingerprintCacheService.tryGetCachedFindings(anyString(), anyString(), any(ModelProviderEnum.class), anyBoolean(), anyInt()))
                .thenReturn(Optional.of(List.of()));

        ReviewService service = new ReviewService(
                taskRepository,
                findingRepository,
                aiModelService,
                codeParserService,
                ruleEngineService,
                configService,
                gitService,
                fingerprintCacheService,
                null,
                auditService,
                provenanceService
        );

        ReviewRequestDTO request = ReviewRequestDTO.builder()
                .rulesOnly(false)
                .modelProvider("QWEN")
                .enableRag(true)
                .ruleTemplate("ALIBABA")
                .build();

        Method method = ReviewService.class.getDeclaredMethod("executeReviewStrategy", String.class, String.class, ReviewRequestDTO.class);
        method.setAccessible(true);
        List<Finding> findings = (List<Finding>) method.invoke(service, "class A {}", "Java", request);

        assertEquals(1, findings.size());
        assertEquals("SQL注入风险", findings.get(0).getTitle());
        verify(aiModelService, never()).reviewCode(anyString(), anyString(), anyString(), anyBoolean());
        verify(aiModelService, never()).reviewCode(anyString(), anyString(), anyString(), anyBoolean(), anyString(), any());
    }

    @Test
    void should_keep_cache_miss_evidence_after_ai_review_clears_context() throws Exception {
        ReviewTaskRepository taskRepository = mock(ReviewTaskRepository.class);
        FindingRepository findingRepository = mock(FindingRepository.class);
        AIModelService aiModelService = mock(AIModelService.class);
        CodeParserService codeParserService = mock(CodeParserService.class);
        RuleEngineService ruleEngineService = mock(RuleEngineService.class);
        SystemConfigService configService = mock(SystemConfigService.class);
        GitService gitService = mock(GitService.class);
        SemanticFingerprintCacheService fingerprintCacheService = mock(SemanticFingerprintCacheService.class);
        ReviewAuditService auditService = mock(ReviewAuditService.class);
        ReviewProvenanceService provenanceService = mock(ReviewProvenanceService.class);

        SettingsDTO settings = new SettingsDTO();
        settings.setRuleCategories(java.util.Collections.emptyMap());
        when(configService.getSettings()).thenReturn(settings);

        Finding aiFinding = new Finding();
        aiFinding.setSeverity(2);
        aiFinding.setTitle("ai finding");
        aiFinding.setLocation("L2");
        aiFinding.setDescription("d");

        when(fingerprintCacheService.tryGetCachedFindings(anyString(), anyString(), any(ModelProviderEnum.class), anyBoolean(), anyInt()))
                .thenReturn(Optional.empty());
        when(aiModelService.reviewCode(anyString(), anyString(), anyString(), anyBoolean(), anyString(), any()))
                .thenAnswer(invocation -> {
                    ReviewContextHolder.clear();
                    return List.of(aiFinding);
                });

        ReviewService service = new ReviewService(
                taskRepository,
                findingRepository,
                aiModelService,
                codeParserService,
                ruleEngineService,
                configService,
                gitService,
                fingerprintCacheService,
                null,
                auditService,
                provenanceService
        );

        ReviewRequestDTO request = ReviewRequestDTO.builder()
                .rulesOnly(false)
                .modelProvider("QWEN")
                .enableRag(true)
                .build();

        Method method = ReviewService.class.getDeclaredMethod("executeReviewStrategy",
                String.class, String.class, ReviewRequestDTO.class, Long.class, String.class);
        method.setAccessible(true);
        List<Finding> findings = (List<Finding>) method.invoke(service, "class A {}", "Java", request, 8L, "A.java");
        List<String> evidenceTypes = ReviewContextHolder.getEvidence().stream()
                .map(EvidenceDraft::getEvidenceType)
                .toList();

        assertEquals(1, findings.size());
        assertTrue(evidenceTypes.contains("SEMANTIC_CACHE_MISS"));
        assertTrue(evidenceTypes.contains("SEMANTIC_CACHE_STORE"));
        verify(auditService).record(eq(8L), eq("SEMANTIC_CACHE_MISS"), eq("CACHE"), eq("system"), anyString(), anyMap());
        verify(auditService).record(eq(8L), eq("AI_REVIEW_COMPLETED"), eq("MODEL"), eq("system"), anyString(), anyMap());
        verify(auditService).record(eq(8L), eq("SEMANTIC_CACHE_STORE"), eq("CACHE"), eq("system"), anyString(), anyMap());
    }
}
