package com.codeguardian.service;

import com.codeguardian.dto.ReviewRequestDTO;
import com.codeguardian.dto.ReviewResponseDTO;
import com.codeguardian.entity.ReviewTask;
import com.codeguardian.repository.FindingRepository;
import com.codeguardian.repository.ReviewTaskRepository;
import com.codeguardian.service.cache.SemanticFingerprintCacheService;
import com.codeguardian.service.provenance.ReviewAuditService;
import com.codeguardian.service.provenance.ReviewProvenanceService;
import com.codeguardian.service.rules.RuleEngineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewServiceTypeTest {

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

    @InjectMocks
    private ReviewService reviewService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void should_save_correct_review_type_when_file_review_created() {
        // Arrange
        ReviewRequestDTO request = ReviewRequestDTO.builder()
                .reviewType("FILE")
                .filePath("/path/to/file.java")
                .taskName("Test Task")
                .build();

        when(taskRepository.save(any(ReviewTask.class))).thenAnswer(invocation -> {
            ReviewTask task = invocation.getArgument(0);
            task.setId(1L); // Simulate DB ID generation
            return task;
        });

        // Act
        reviewService.createReviewTask(request);

        // Assert
        ArgumentCaptor<ReviewTask> taskCaptor = ArgumentCaptor.forClass(ReviewTask.class);
        verify(taskRepository, org.mockito.Mockito.atLeastOnce()).save(taskCaptor.capture());
        
        // Check the first save (creation) or the last save (update status)
        // In both cases, reviewType should be preserved.
        ReviewTask savedTask = taskCaptor.getAllValues().get(0);
        assertEquals(com.codeguardian.enums.ReviewTypeEnum.FILE.getValue(), savedTask.getReviewType());
    }
}
