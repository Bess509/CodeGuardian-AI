package com.codeguardian.controller;

import com.codeguardian.entity.ReviewTask;
import com.codeguardian.repository.FindingRepository;
import com.codeguardian.repository.ReviewAuditEventRepository;
import com.codeguardian.repository.ReviewEvidenceRepository;
import com.codeguardian.repository.ReviewTaskRepository;
import com.codeguardian.service.GitService;
import com.codeguardian.service.ReviewService;
import com.codeguardian.service.SystemConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewControllerDeleteTaskTest {

    @Test
    void should_reject_physical_delete_when_task_has_proof_records() {
        Fixtures fixtures = fixtures();
        ReviewTask task = ReviewTask.builder().id(7L).build();
        when(fixtures.taskRepository.findById(7L)).thenReturn(Optional.of(task));
        when(fixtures.evidenceRepository.countByTaskId(7L)).thenReturn(2L);
        when(fixtures.auditEventRepository.countByTaskId(7L)).thenReturn(3L);

        ResponseEntity<Void> response = fixtures.controller.deleteTask(7L);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        verify(fixtures.taskRepository, never()).delete(task);
    }

    @Test
    void should_allow_delete_before_any_proof_record_exists() {
        Fixtures fixtures = fixtures();
        ReviewTask task = ReviewTask.builder().id(8L).build();
        when(fixtures.taskRepository.findById(8L)).thenReturn(Optional.of(task));
        when(fixtures.evidenceRepository.countByTaskId(8L)).thenReturn(0L);
        when(fixtures.auditEventRepository.countByTaskId(8L)).thenReturn(0L);

        ResponseEntity<Void> response = fixtures.controller.deleteTask(8L);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(fixtures.taskRepository).delete(task);
    }

    private Fixtures fixtures() {
        ReviewTaskRepository taskRepository = mock(ReviewTaskRepository.class);
        FindingRepository findingRepository = mock(FindingRepository.class);
        ReviewEvidenceRepository evidenceRepository = mock(ReviewEvidenceRepository.class);
        ReviewAuditEventRepository auditEventRepository = mock(ReviewAuditEventRepository.class);
        ReviewController controller = new ReviewController(
                mock(ReviewService.class),
                taskRepository,
                findingRepository,
                evidenceRepository,
                auditEventRepository,
                mock(GitService.class),
                mock(SystemConfigService.class)
        );
        return new Fixtures(taskRepository, evidenceRepository, auditEventRepository, controller);
    }

    private record Fixtures(ReviewTaskRepository taskRepository,
                            ReviewEvidenceRepository evidenceRepository,
                            ReviewAuditEventRepository auditEventRepository,
                            ReviewController controller) {
    }
}
