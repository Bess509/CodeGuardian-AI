package com.codeguardian.service;

import com.codeguardian.dto.ReviewRequestDTO;
import com.codeguardian.dto.ReviewResponseDTO;
import com.codeguardian.entity.ReviewTask;
import com.codeguardian.model.dto.SettingsDTO;
import com.codeguardian.repository.FindingRepository;
import com.codeguardian.repository.ReviewTaskRepository;
import com.codeguardian.service.agent.ReviewAgentOrchestrator;
import com.codeguardian.service.cache.SemanticFingerprintCacheService;
import com.codeguardian.service.provenance.ReviewAuditService;
import com.codeguardian.service.provenance.ReviewProvenanceService;
import com.codeguardian.service.rules.RuleEngineService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewServiceSessionLinkTest {

    @Mock
    private ReviewTaskRepository taskRepository;
    @Mock
    private FindingRepository findingRepository;
    @Mock
    private AIModelService aiModelService;
    @Mock
    private CodeParserService codeParserService;
    @Mock
    private RuleEngineService ruleEngineService;
    @Mock
    private SystemConfigService configService;
    @Mock
    private GitService gitService;
    @Mock
    private SemanticFingerprintCacheService fingerprintCacheService;
    @Mock
    private ReviewAgentOrchestrator reviewAgentOrchestrator;
    @Mock
    private ReviewAuditService auditService;
    @Mock
    private ReviewProvenanceService provenanceService;

    @Test
    void createReviewTaskPersistsSessionAndProjectLinks() {
        ReviewService reviewService = new ReviewService(
                taskRepository,
                findingRepository,
                aiModelService,
                codeParserService,
                ruleEngineService,
                configService,
                gitService,
                fingerprintCacheService,
                reviewAgentOrchestrator,
                auditService,
                provenanceService
        );
        when(taskRepository.save(any(ReviewTask.class))).thenAnswer(invocation -> {
            ReviewTask task = invocation.getArgument(0);
            task.setId(1L);
            return task;
        });
        when(findingRepository.findByTaskId(1L)).thenReturn(List.of());
        lenient().when(configService.getSettings()).thenReturn(new SettingsDTO());
        ReviewRequestDTO request = ReviewRequestDTO.builder()
                .reviewType("SNIPPET")
                .codeSnippet("class A {}")
                .sessionId(99L)
                .projectKey("repo:payment")
                .build();

        ReviewResponseDTO response = reviewService.createReviewTask(request);

        ArgumentCaptor<ReviewTask> captor = ArgumentCaptor.forClass(ReviewTask.class);
        verify(taskRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getSessionId()).isEqualTo(99L);
        assertThat(captor.getAllValues().get(0).getProjectKey()).isEqualTo("repo:payment");
        assertThat(response.getSessionId()).isEqualTo(99L);
        assertThat(response.getProjectKey()).isEqualTo("repo:payment");
    }
}
