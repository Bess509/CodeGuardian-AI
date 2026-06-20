package com.codeguardian.service.session;

import com.codeguardian.entity.ReviewSessionMemory;
import com.codeguardian.repository.ReviewSessionMemoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewMemoryServiceTest {

    @Mock
    private ReviewSessionMemoryRepository memoryRepository;

    @Test
    void recallFiltersByUserProjectActiveStatusAndRanksBySimilarityConfidenceAndRecency() {
        ReviewMemoryService service = new ReviewMemoryService(memoryRepository);
        when(memoryRepository.findRecallCandidates(7L, "repo:payment", "ACTIVE"))
                .thenReturn(List.of(
                        memory(1L, "SQL injection appears repeatedly in mapper layer", 0.8, LocalDateTime.now().minusDays(2)),
                        memory(2L, "Frontend prefers dark mode", 1.0, LocalDateTime.now()),
                        memory(3L, "Payment module uses MyBatis dynamic SQL", 0.9, LocalDateTime.now().minusHours(1))
                ));

        List<ReviewSessionMemory> result = service.recall(7L, "repo:payment", "SQL risk in payment mapper", 2);

        assertThat(result).extracting(ReviewSessionMemory::getId).containsExactly(3L, 1L);
    }

    @Test
    void createActiveMemoryStoresTraceableUserInput() {
        ReviewMemoryService service = new ReviewMemoryService(memoryRepository);
        when(memoryRepository.save(org.mockito.Mockito.any(ReviewSessionMemory.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.createActiveMemory(
                7L,
                "repo:payment",
                11L,
                "PROJECT_POLICY",
                "Always review payment mapper SQL with security priority.",
                "message-31"
        );

        ArgumentCaptor<ReviewSessionMemory> captor = ArgumentCaptor.forClass(ReviewSessionMemory.class);
        verify(memoryRepository).save(captor.capture());
        ReviewSessionMemory saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(7L);
        assertThat(saved.getProjectKey()).isEqualTo("repo:payment");
        assertThat(saved.getSessionId()).isEqualTo(11L);
        assertThat(saved.getScope()).isEqualTo("PROJECT_MEMORY");
        assertThat(saved.getSourceType()).isEqualTo("USER_INPUT");
        assertThat(saved.getSourceId()).isEqualTo("message-31");
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getConfidence()).isEqualTo(1.0d);
        assertThat(saved.getEmbeddingText()).contains("payment mapper SQL");
    }

    private ReviewSessionMemory memory(Long id, String content, Double confidence, LocalDateTime lastUsedAt) {
        return ReviewSessionMemory.builder()
                .id(id)
                .userId(7L)
                .projectKey("repo:payment")
                .scope("PROJECT_MEMORY")
                .memoryType("PROJECT_PATTERN")
                .content(content)
                .summary(content)
                .status("ACTIVE")
                .sourceType("TASK_REPORT")
                .confidence(confidence)
                .lastUsedAt(lastUsedAt)
                .build();
    }
}
