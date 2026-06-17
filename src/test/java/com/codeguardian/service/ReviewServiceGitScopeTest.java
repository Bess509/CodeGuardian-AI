package com.codeguardian.service;

import com.codeguardian.dto.ReviewRequestDTO;
import com.codeguardian.entity.ReviewTask;
import com.codeguardian.repository.ReviewTaskRepository;
import com.codeguardian.repository.FindingRepository;
import com.codeguardian.service.cache.SemanticFingerprintCacheService;
import com.codeguardian.service.provenance.ReviewAuditService;
import com.codeguardian.service.provenance.ReviewProvenanceService;
import com.codeguardian.service.rules.RuleEngineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class ReviewServiceGitScopeTest {

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
    private ReviewAuditService auditService;
    @Mock
    private ReviewProvenanceService provenanceService;

    private ReviewService reviewService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        reviewService = new ReviewService(taskRepository, findingRepository, aiModelService, codeParserService,
                ruleEngineService, configService, gitService, fingerprintCacheService, null, auditService, provenanceService);
    }

    @Test
    void createReviewTask_should_update_scope_with_git_url_for_git_review() {
        // Arrange
        String gitUrl = "https://github.com/example/repo.git";
        ReviewRequestDTO request = ReviewRequestDTO.builder()
                .reviewType("GIT")
                .gitUrl(gitUrl)
                .projectPath("/tmp/repo") // Mock cloned path
                .build();

        when(taskRepository.save(any(ReviewTask.class))).thenAnswer(invocation -> {
            ReviewTask t = invocation.getArgument(0);
            if (t.getId() == null) t.setId(1L);
            return t;
        });
        com.codeguardian.model.dto.SettingsDTO settings = new com.codeguardian.model.dto.SettingsDTO();
        settings.setIncludePaths("src/main/java");
        settings.setExcludePaths("");
        settings.setRuleCategories(java.util.Collections.emptyMap());
        when(configService.getSettings()).thenReturn(settings);
        when(gitService.cloneRepository(anyString(), any(), any())).thenReturn("/tmp/repo");
        when(codeParserService.scanDirectory(anyString(), any(), any())).thenReturn(java.util.Collections.emptyList());

        // Act
        reviewService.createReviewTask(request);

        // Assert
        ArgumentCaptor<ReviewTask> taskCaptor = ArgumentCaptor.forClass(ReviewTask.class);
        verify(taskRepository, atLeast(2)).save(taskCaptor.capture());

        // Check the last saved state or the intermediate state where scope is updated
        // The service saves:
        // 1. Initial save (scope="git项目")
        // 2. Update scope save (scope=gitUrl)
        // 3. Final save (status=COMPLETED/FAILED)
        
        // We want to verify that one of the saved instances has scope set to gitUrl
        boolean scopeUpdated = taskCaptor.getAllValues().stream()
                .anyMatch(t -> gitUrl.equals(t.getScope()));
        
        assertEquals(true, scopeUpdated, "Task scope should be updated to Git URL");
    }

    @Test
    void performParallelReview_should_audit_empty_scanned_scope() throws Exception {
        ReviewTask task = ReviewTask.builder()
                .id(11L)
                .scope("/tmp/empty-repo")
                .build();
        ReviewRequestDTO request = ReviewRequestDTO.builder()
                .reviewType("DIRECTORY")
                .directoryPath("/tmp/empty-repo")
                .build();
        com.codeguardian.model.dto.SettingsDTO settings = new com.codeguardian.model.dto.SettingsDTO();
        settings.setIncludePaths("src/main/java");
        settings.setExcludePaths("target,node_modules");
        settings.setRuleCategories(java.util.Collections.emptyMap());
        when(configService.getSettings()).thenReturn(settings);
        when(codeParserService.scanDirectory(anyString(), any(), any())).thenReturn(java.util.Collections.emptyList());

        Method method = ReviewService.class.getDeclaredMethod("performParallelReview", ReviewTask.class, ReviewRequestDTO.class);
        method.setAccessible(true);
        method.invoke(reviewService, task, request);

        verify(auditService).record(eq(11L), eq("REVIEW_SCOPE_SCANNED"), eq("DISCOVERY"), eq("system"), anyString(), anyMap());
        verify(auditService).record(eq(11L), eq("NO_ANALYZABLE_FILES"), eq("DISCOVERY"), eq("system"), anyString(), anyMap());
        verify(auditService).record(eq(11L), eq("FINDINGS_SAVED"), eq("PERSISTENCE"), eq("system"), anyString(), anyMap());
        verify(auditService).record(eq(11L), eq("REVIEW_BATCH_COMPLETED"), eq("ANALYSIS"), eq("system"), anyString(), anyMap());
    }
}
