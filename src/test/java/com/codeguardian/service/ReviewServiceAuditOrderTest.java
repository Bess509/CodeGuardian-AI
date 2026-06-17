package com.codeguardian.service;

import com.codeguardian.dto.ReviewRequestDTO;
import com.codeguardian.entity.ReviewTask;
import com.codeguardian.enums.ReviewTypeEnum;
import com.codeguardian.enums.TaskStatusEnum;
import com.codeguardian.repository.FindingRepository;
import com.codeguardian.repository.ReviewTaskRepository;
import com.codeguardian.model.dto.SettingsDTO;
import com.codeguardian.service.cache.SemanticFingerprintCacheService;
import com.codeguardian.service.provenance.ReviewAuditService;
import com.codeguardian.service.provenance.ReviewProvenanceService;
import com.codeguardian.service.rag.TaskRagPackService;
import com.codeguardian.service.rules.RuleEngineService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReviewServiceAuditOrderTest {

    @Test
    void should_evict_task_rag_pack_before_task_completed_audit_event() throws Exception {
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
        TaskRagPackService taskRagPackService = mock(TaskRagPackService.class);

        ReviewTask task = ReviewTask.builder()
                .id(42L)
                .name("audit order")
                .reviewType(ReviewTypeEnum.SNIPPET.getValue())
                .scope("class A {}")
                .status(TaskStatusEnum.PENDING.getValue())
                .build();
        when(taskRepository.findById(42L)).thenReturn(Optional.of(task));
        SettingsDTO settings = new SettingsDTO();
        settings.setRuleCategories(java.util.Collections.emptyMap());
        when(configService.getSettings()).thenReturn(settings);
        when(ruleEngineService.reviewWithTemplate(anyString(), anyString(), any()))
                .thenReturn(List.of());

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
        Field ragPackField = ReviewService.class.getDeclaredField("taskRagPackService");
        ragPackField.setAccessible(true);
        ragPackField.set(service, taskRagPackService);

        ReviewRequestDTO request = ReviewRequestDTO.builder()
                .reviewType("SNIPPET")
                .language("Java")
                .codeSnippet("class A {}")
                .rulesOnly(true)
                .enableRag(true)
                .build();

        Method runReviewAsync = ReviewService.class.getDeclaredMethod(
                "runReviewAsync", Long.class, ReviewTask.class, ReviewRequestDTO.class);
        runReviewAsync.setAccessible(true);
        runReviewAsync.invoke(service, 42L, task, request);

        InOrder inOrder = inOrder(taskRagPackService, auditService);
        inOrder.verify(taskRagPackService).evict(42L);
        inOrder.verify(auditService).record(eq(42L), eq("TASK_COMPLETED"), eq("ORCHESTRATION"), eq("system"),
                anyString(), anyMap());
    }
}
