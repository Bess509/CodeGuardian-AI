package com.codeguardian.service.provenance;

import com.codeguardian.config.AuditSigningProperties;
import com.codeguardian.entity.ReviewAuditEvent;
import com.codeguardian.enums.TaskStatusEnum;
import com.codeguardian.repository.ReviewAuditEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReviewAuditServiceTest {

    @Test
    void should_create_and_verify_hash_chain() {
        ReviewAuditEventRepository repository = mock(ReviewAuditEventRepository.class);
        ProvenanceHashService hashService = new ProvenanceHashService(new ObjectMapper().findAndRegisterModules());
        ReviewAuditService service = new ReviewAuditService(repository, hashService);
        List<ReviewAuditEvent> stored = new ArrayList<>();

        when(repository.findTopByTaskIdOrderByIdDesc(1L)).thenAnswer(invocation ->
                stored.isEmpty() ? Optional.empty() : Optional.of(stored.get(stored.size() - 1)));
        when(repository.save(any(ReviewAuditEvent.class))).thenAnswer(invocation -> {
            ReviewAuditEvent event = invocation.getArgument(0);
            event.setId((long) stored.size() + 1);
            stored.add(event);
            return event;
        });
        when(repository.findByTaskIdOrderByIdAsc(1L)).thenReturn(stored);

        ReviewAuditEvent created = service.record(1L, "TASK_CREATED", "SUBMIT", "system",
                "created", Map.of("scope", "snippet"));
        ReviewAuditEvent completed = service.record(1L, "TASK_COMPLETED", "ORCHESTRATION", "system",
                "completed", Map.of("findingCount", 2));

        assertNotNull(created.getEventHash());
        assertEquals(created.getEventHash(), completed.getPreviousHash());
        ReviewAuditService.IntegrityResult result = service.verifyTaskChain(1L);
        assertTrue(result.isValid());
        assertEquals(2, result.getEventCount());
        assertTrue(result.isSignatureValid());
        assertEquals(0, result.getSignedEventCount());
    }

    @Test
    void should_detect_tampered_chain() {
        ReviewAuditEventRepository repository = mock(ReviewAuditEventRepository.class);
        ProvenanceHashService hashService = new ProvenanceHashService(new ObjectMapper().findAndRegisterModules());
        ReviewAuditService service = new ReviewAuditService(repository, hashService);

        ReviewAuditEvent event = ReviewAuditEvent.builder()
                .id(1L)
                .taskId(1L)
                .eventType("TASK_CREATED")
                .stage("SUBMIT")
                .actor("system")
                .message("created")
                .metadata(Map.of("scope", "snippet"))
                .payloadHash("bad")
                .previousHash("0".repeat(64))
                .eventHash("bad")
                .createdAt(java.time.LocalDateTime.now())
                .build();
        when(repository.findByTaskIdOrderByIdAsc(1L)).thenReturn(List.of(event));

        ReviewAuditService.IntegrityResult result = service.verifyTaskChain(1L);
        assertFalse(result.isValid());
        assertEquals(1L, result.getFailedEventId());
    }

    @Test
    void should_sign_and_verify_audit_events_when_signing_is_enabled() {
        ReviewAuditEventRepository repository = mock(ReviewAuditEventRepository.class);
        ProvenanceHashService hashService = new ProvenanceHashService(new ObjectMapper().findAndRegisterModules());
        AuditSigningProperties signing = signingProperties();
        ReviewAuditService service = new ReviewAuditService(repository, hashService, signing);
        List<ReviewAuditEvent> stored = new ArrayList<>();

        when(repository.findTopByTaskIdOrderByIdDesc(1L)).thenAnswer(invocation ->
                stored.isEmpty() ? Optional.empty() : Optional.of(stored.get(stored.size() - 1)));
        when(repository.save(any(ReviewAuditEvent.class))).thenAnswer(invocation -> {
            ReviewAuditEvent event = invocation.getArgument(0);
            event.setId((long) stored.size() + 1);
            stored.add(event);
            return event;
        });
        when(repository.findByTaskIdOrderByIdAsc(1L)).thenReturn(stored);

        ReviewAuditEvent created = service.record(1L, "TASK_CREATED", "SUBMIT", "system",
                "created", Map.of("scope", "snippet"));

        assertEquals("unit-test-key", created.getSignatureKeyId());
        assertEquals("HmacSHA256", created.getSignatureAlgorithm());
        assertNotNull(created.getEventSignature());
        assertEquals(64, created.getEventSignature().length());

        ReviewAuditService.IntegrityResult result = service.verifyTaskChain(1L);

        assertTrue(result.isValid());
        assertTrue(result.isSignatureValid());
        assertEquals(1, result.getSignedEventCount());
        assertEquals("unit-test-key", result.getSignatureKeyId());
    }

    @Test
    void should_detect_tampered_audit_signature() {
        ReviewAuditEventRepository repository = mock(ReviewAuditEventRepository.class);
        ProvenanceHashService hashService = new ProvenanceHashService(new ObjectMapper().findAndRegisterModules());
        AuditSigningProperties signing = signingProperties();
        ReviewAuditService service = new ReviewAuditService(repository, hashService, signing);
        List<ReviewAuditEvent> stored = new ArrayList<>();

        when(repository.findTopByTaskIdOrderByIdDesc(1L)).thenAnswer(invocation ->
                stored.isEmpty() ? Optional.empty() : Optional.of(stored.get(stored.size() - 1)));
        when(repository.save(any(ReviewAuditEvent.class))).thenAnswer(invocation -> {
            ReviewAuditEvent event = invocation.getArgument(0);
            event.setId((long) stored.size() + 1);
            stored.add(event);
            return event;
        });
        when(repository.findByTaskIdOrderByIdAsc(1L)).thenReturn(stored);

        ReviewAuditEvent created = service.record(1L, "TASK_CREATED", "SUBMIT", "system",
                "created", Map.of("scope", "snippet"));
        created.setEventSignature("bad");

        ReviewAuditService.IntegrityResult result = service.verifyTaskChain(1L);

        assertFalse(result.isValid());
        assertFalse(result.isSignatureValid());
        assertEquals("signature_mismatch", result.getReason());
        assertEquals(1L, result.getFailedEventId());
    }

    @Test
    void should_verify_required_audit_coverage_for_completed_task() {
        ReviewAuditEventRepository repository = mock(ReviewAuditEventRepository.class);
        ProvenanceHashService hashService = new ProvenanceHashService(new ObjectMapper().findAndRegisterModules());
        ReviewAuditService service = new ReviewAuditService(repository, hashService);
        List<ReviewAuditEvent> stored = new ArrayList<>();

        when(repository.findTopByTaskIdOrderByIdDesc(2L)).thenAnswer(invocation ->
                stored.isEmpty() ? Optional.empty() : Optional.of(stored.get(stored.size() - 1)));
        when(repository.save(any(ReviewAuditEvent.class))).thenAnswer(invocation -> {
            ReviewAuditEvent event = invocation.getArgument(0);
            event.setId((long) stored.size() + 1);
            stored.add(event);
            return event;
        });
        when(repository.findByTaskIdOrderByIdAsc(2L)).thenReturn(stored);

        service.record(2L, "TASK_CREATED", "SUBMIT", "system", "created", Map.of());
        service.record(2L, "TASK_STARTED", "ORCHESTRATION", "system", "started", Map.of());
        service.record(2L, "REVIEW_STRATEGY_STARTED", "ANALYSIS", "system", "analysis started", Map.of());
        service.record(2L, "REVIEW_STRATEGY_COMPLETED", "ANALYSIS", "system", "analysis completed", Map.of());
        service.record(2L, "FINDINGS_SAVED", "PERSISTENCE", "system", "findings saved", Map.of());
        service.record(2L, "TASK_COMPLETED", "ORCHESTRATION", "system", "completed", Map.of());

        ReviewAuditService.IntegrityResult result = service.verifyTaskChain(2L, TaskStatusEnum.COMPLETED.getValue());

        assertTrue(result.isValid());
        assertTrue(result.isAuditCoverageValid());
        assertTrue(result.isAuditOrderValid());
        assertTrue(result.isTerminalEventConsistent());
        assertEquals(List.of(), result.getMissingEventTypes());
        assertEquals(List.of(), result.getAuditOrderViolations());
    }

    @Test
    void should_detect_missing_required_audit_events_for_completed_task() {
        ReviewAuditEventRepository repository = mock(ReviewAuditEventRepository.class);
        ProvenanceHashService hashService = new ProvenanceHashService(new ObjectMapper().findAndRegisterModules());
        ReviewAuditService service = new ReviewAuditService(repository, hashService);
        List<ReviewAuditEvent> stored = new ArrayList<>();

        when(repository.findTopByTaskIdOrderByIdDesc(3L)).thenAnswer(invocation ->
                stored.isEmpty() ? Optional.empty() : Optional.of(stored.get(stored.size() - 1)));
        when(repository.save(any(ReviewAuditEvent.class))).thenAnswer(invocation -> {
            ReviewAuditEvent event = invocation.getArgument(0);
            event.setId((long) stored.size() + 1);
            stored.add(event);
            return event;
        });
        when(repository.findByTaskIdOrderByIdAsc(3L)).thenReturn(stored);

        service.record(3L, "TASK_CREATED", "SUBMIT", "system", "created", Map.of());
        service.record(3L, "TASK_STARTED", "ORCHESTRATION", "system", "started", Map.of());

        ReviewAuditService.IntegrityResult result = service.verifyTaskChain(3L, TaskStatusEnum.COMPLETED.getValue());

        assertTrue(result.isValid());
        assertFalse(result.isAuditCoverageValid());
        assertFalse(result.isTerminalEventConsistent());
        assertEquals(List.of(
                "REVIEW_STRATEGY_STARTED",
                "REVIEW_STRATEGY_COMPLETED",
                "FINDINGS_SAVED",
                "TASK_COMPLETED"
        ), result.getMissingEventTypes());
    }

    @Test
    void should_reject_completed_task_without_analysis_and_persistence_events() {
        ReviewAuditEventRepository repository = mock(ReviewAuditEventRepository.class);
        ProvenanceHashService hashService = new ProvenanceHashService(new ObjectMapper().findAndRegisterModules());
        ReviewAuditService service = new ReviewAuditService(repository, hashService);
        List<ReviewAuditEvent> stored = new ArrayList<>();

        when(repository.findTopByTaskIdOrderByIdDesc(4L)).thenAnswer(invocation ->
                stored.isEmpty() ? Optional.empty() : Optional.of(stored.get(stored.size() - 1)));
        when(repository.save(any(ReviewAuditEvent.class))).thenAnswer(invocation -> {
            ReviewAuditEvent event = invocation.getArgument(0);
            event.setId((long) stored.size() + 1);
            stored.add(event);
            return event;
        });
        when(repository.findByTaskIdOrderByIdAsc(4L)).thenReturn(stored);

        service.record(4L, "TASK_CREATED", "SUBMIT", "system", "created", Map.of());
        service.record(4L, "TASK_STARTED", "ORCHESTRATION", "system", "started", Map.of());
        service.record(4L, "TASK_COMPLETED", "ORCHESTRATION", "system", "completed", Map.of());

        ReviewAuditService.IntegrityResult result = service.verifyTaskChain(4L, TaskStatusEnum.COMPLETED.getValue());

        assertTrue(result.isValid());
        assertFalse(result.isAuditCoverageValid());
        assertTrue(result.isTerminalEventConsistent());
        assertEquals(List.of(
                "REVIEW_STRATEGY_STARTED",
                "REVIEW_STRATEGY_COMPLETED",
                "FINDINGS_SAVED"
        ), result.getMissingEventTypes());
    }

    @Test
    void should_accept_completed_task_with_no_analyzable_files_proof() {
        ReviewAuditEventRepository repository = mock(ReviewAuditEventRepository.class);
        ProvenanceHashService hashService = new ProvenanceHashService(new ObjectMapper().findAndRegisterModules());
        ReviewAuditService service = new ReviewAuditService(repository, hashService);
        List<ReviewAuditEvent> stored = new ArrayList<>();

        when(repository.findTopByTaskIdOrderByIdDesc(5L)).thenAnswer(invocation ->
                stored.isEmpty() ? Optional.empty() : Optional.of(stored.get(stored.size() - 1)));
        when(repository.save(any(ReviewAuditEvent.class))).thenAnswer(invocation -> {
            ReviewAuditEvent event = invocation.getArgument(0);
            event.setId((long) stored.size() + 1);
            stored.add(event);
            return event;
        });
        when(repository.findByTaskIdOrderByIdAsc(5L)).thenReturn(stored);

        service.record(5L, "TASK_CREATED", "SUBMIT", "system", "created", Map.of());
        service.record(5L, "TASK_STARTED", "ORCHESTRATION", "system", "started", Map.of());
        service.record(5L, "REVIEW_SCOPE_SCANNED", "DISCOVERY", "system", "scope scanned", Map.of("fileCount", 0));
        service.record(5L, "NO_ANALYZABLE_FILES", "DISCOVERY", "system", "no analyzable files", Map.of("fileCount", 0));
        service.record(5L, "FINDINGS_SAVED", "PERSISTENCE", "system", "zero findings saved", Map.of("findingCount", 0));
        service.record(5L, "REVIEW_BATCH_COMPLETED", "ORCHESTRATION", "system", "batch completed", Map.of("findingCount", 0));
        service.record(5L, "TASK_COMPLETED", "ORCHESTRATION", "system", "completed", Map.of());

        ReviewAuditService.IntegrityResult result = service.verifyTaskChain(5L, TaskStatusEnum.COMPLETED.getValue());

        assertTrue(result.isValid());
        assertTrue(result.isAuditCoverageValid());
        assertTrue(result.isAuditOrderValid());
        assertTrue(result.isTerminalEventConsistent());
        assertEquals(List.of(), result.getMissingEventTypes());
        assertEquals(List.of(), result.getAuditOrderViolations());
    }

    @Test
    void should_reject_completed_task_with_out_of_order_audit_events() {
        ReviewAuditEventRepository repository = mock(ReviewAuditEventRepository.class);
        ProvenanceHashService hashService = new ProvenanceHashService(new ObjectMapper().findAndRegisterModules());
        ReviewAuditService service = new ReviewAuditService(repository, hashService);
        List<ReviewAuditEvent> stored = new ArrayList<>();

        when(repository.findTopByTaskIdOrderByIdDesc(6L)).thenAnswer(invocation ->
                stored.isEmpty() ? Optional.empty() : Optional.of(stored.get(stored.size() - 1)));
        when(repository.save(any(ReviewAuditEvent.class))).thenAnswer(invocation -> {
            ReviewAuditEvent event = invocation.getArgument(0);
            event.setId((long) stored.size() + 1);
            stored.add(event);
            return event;
        });
        when(repository.findByTaskIdOrderByIdAsc(6L)).thenReturn(stored);

        service.record(6L, "TASK_CREATED", "SUBMIT", "system", "created", Map.of());
        service.record(6L, "TASK_STARTED", "ORCHESTRATION", "system", "started", Map.of());
        service.record(6L, "REVIEW_STRATEGY_COMPLETED", "ANALYSIS", "system", "analysis completed", Map.of());
        service.record(6L, "REVIEW_STRATEGY_STARTED", "ANALYSIS", "system", "analysis started", Map.of());
        service.record(6L, "FINDINGS_SAVED", "PERSISTENCE", "system", "findings saved", Map.of());
        service.record(6L, "TASK_COMPLETED", "ORCHESTRATION", "system", "completed", Map.of());

        ReviewAuditService.IntegrityResult result = service.verifyTaskChain(6L, TaskStatusEnum.COMPLETED.getValue());

        assertTrue(result.isValid());
        assertFalse(result.isAuditCoverageValid());
        assertFalse(result.isAuditOrderValid());
        assertTrue(result.isTerminalEventConsistent());
        assertEquals(List.of(), result.getMissingEventTypes());
        assertEquals(List.of("REVIEW_STRATEGY_STARTED_after_REVIEW_STRATEGY_COMPLETED"), result.getAuditOrderViolations());
    }

    @Test
    void should_store_empty_metadata_when_recording_null_metadata() {
        ReviewAuditEventRepository repository = mock(ReviewAuditEventRepository.class);
        ProvenanceHashService hashService = new ProvenanceHashService(new ObjectMapper().findAndRegisterModules());
        ReviewAuditService service = new ReviewAuditService(repository, hashService);
        List<ReviewAuditEvent> stored = new ArrayList<>();

        when(repository.findTopByTaskIdOrderByIdDesc(7L)).thenAnswer(invocation ->
                stored.isEmpty() ? Optional.empty() : Optional.of(stored.get(stored.size() - 1)));
        when(repository.save(any(ReviewAuditEvent.class))).thenAnswer(invocation -> {
            ReviewAuditEvent event = invocation.getArgument(0);
            event.setId((long) stored.size() + 1);
            stored.add(event);
            return event;
        });
        when(repository.findByTaskIdOrderByIdAsc(7L)).thenReturn(stored);

        ReviewAuditEvent event = service.record(7L, "TASK_COMPLETED", "ORCHESTRATION", "system", "completed", null);
        ReviewAuditService.IntegrityResult result = service.verifyTaskChain(7L);

        assertEquals(Map.of(), event.getMetadata());
        assertTrue(result.isValid());
        assertEquals("ok", result.getReason());
    }

    @Test
    void should_reject_audit_event_updates_at_entity_boundary() {
        ReviewAuditEvent event = ReviewAuditEvent.builder()
                .id(1L)
                .taskId(1L)
                .eventType("TASK_CREATED")
                .eventHash("a".repeat(64))
                .createdAt(java.time.LocalDateTime.now())
                .build();

        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class, event::preventUpdate);

        assertTrue(error.getMessage().contains("append-only"));
    }

    @Test
    void should_mark_audit_event_columns_as_not_updatable() throws Exception {
        for (String fieldName : List.of(
                "taskId",
                "eventType",
                "stage",
                "actor",
                "message",
                "payloadHash",
                "previousHash",
                "eventHash",
                "signatureKeyId",
                "signatureAlgorithm",
                "eventSignature",
                "metadata",
                "createdAt"
        )) {
            Field field = ReviewAuditEvent.class.getDeclaredField(fieldName);
            Column column = field.getAnnotation(Column.class);
            assertNotNull(column, fieldName + " should be a persistent column");
            assertFalse(column.updatable(), fieldName + " should be append-only");
        }
    }

    private AuditSigningProperties signingProperties() {
        AuditSigningProperties signing = new AuditSigningProperties();
        signing.setEnabled(true);
        signing.setKeyId("unit-test-key");
        signing.setSecret("super-secret-test-key");
        return signing;
    }
}
