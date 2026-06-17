package com.codeguardian.service.provenance;

import com.codeguardian.entity.Finding;
import com.codeguardian.entity.ReviewEvidence;
import com.codeguardian.entity.ReviewTask;
import com.codeguardian.repository.FindingRepository;
import com.codeguardian.repository.ReviewEvidenceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.Column;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewProvenanceServiceTest {

    @Test
    void should_ground_finding_with_source_excerpt_and_hash() {
        ReviewEvidenceRepository evidenceRepository = mock(ReviewEvidenceRepository.class);
        FindingRepository findingRepository = mock(FindingRepository.class);
        ProvenanceHashService hashService = new ProvenanceHashService(new ObjectMapper().findAndRegisterModules());
        ReviewProvenanceService service = new ReviewProvenanceService(evidenceRepository, findingRepository, hashService);
        List<ReviewEvidence> savedEvidence = new ArrayList<>();
        AtomicLong sequence = new AtomicLong(99L);

        when(evidenceRepository.save(any(ReviewEvidence.class))).thenAnswer(invocation -> {
            ReviewEvidence evidence = invocation.getArgument(0);
            evidence.setId(sequence.getAndIncrement());
            savedEvidence.add(evidence);
            return evidence;
        });
        when(evidenceRepository.countByFindingId(10L)).thenAnswer(invocation -> savedEvidence.stream()
                .filter(e -> Long.valueOf(10L).equals(e.getFindingId()))
                .count());
        when(evidenceRepository.findByFindingIdOrderByCreatedAtAscIdAsc(10L)).thenAnswer(invocation -> savedEvidence.stream()
                .filter(e -> Long.valueOf(10L).equals(e.getFindingId()))
                .toList());

        ReviewTask task = ReviewTask.builder().id(1L).build();
        Finding finding = Finding.builder()
                .id(10L)
                .startLine(2)
                .endLine(2)
                .source("AI Model")
                .build();

        List<ReviewEvidence> evidence = service.groundFindingWithSource(
                task,
                finding,
                "src/App.java",
                "Java",
                "class App {\n  void run() {}\n}"
        );

        assertEquals(1, evidence.size());
        assertTrue(evidence.get(0).getExcerpt().contains("2:   void run() {}"));
        assertTrue(Boolean.TRUE.equals(finding.getGrounded()));
        assertEquals(1, finding.getEvidenceCount());
        assertEquals(64, finding.getEvidenceHash().length());
        verify(findingRepository).save(finding);
    }

    @Test
    void should_attach_context_evidence_to_finding_and_update_grounding() {
        ReviewEvidenceRepository evidenceRepository = mock(ReviewEvidenceRepository.class);
        FindingRepository findingRepository = mock(FindingRepository.class);
        ProvenanceHashService hashService = new ProvenanceHashService(new ObjectMapper().findAndRegisterModules());
        ReviewProvenanceService service = new ReviewProvenanceService(evidenceRepository, findingRepository, hashService);
        List<ReviewEvidence> savedEvidence = new ArrayList<>();
        AtomicLong sequence = new AtomicLong(1L);

        when(evidenceRepository.save(any(ReviewEvidence.class))).thenAnswer(invocation -> {
            ReviewEvidence evidence = invocation.getArgument(0);
            evidence.setId(sequence.getAndIncrement());
            savedEvidence.add(evidence);
            return evidence;
        });
        when(evidenceRepository.countByFindingId(10L)).thenAnswer(invocation -> savedEvidence.stream()
                .filter(e -> Long.valueOf(10L).equals(e.getFindingId()))
                .count());
        when(evidenceRepository.findByFindingIdOrderByCreatedAtAscIdAsc(10L)).thenAnswer(invocation -> savedEvidence.stream()
                .filter(e -> Long.valueOf(10L).equals(e.getFindingId()))
                .toList());

        ReviewTask task = ReviewTask.builder().id(1L).build();
        Finding finding = Finding.builder()
                .id(10L)
                .severity(com.codeguardian.enums.SeverityEnum.HIGH.getValue())
                .category("SECURITY")
                .location("src/App.java:2")
                .build();
        List<EvidenceDraft> drafts = List.of(
                EvidenceDraft.builder()
                        .evidenceType("RAG_SNIPPET")
                        .sourceName("KnowledgeBaseService")
                        .sourceRef("knowledge://document/doc1#chunk=chunk1")
                        .locator("rank:1")
                        .excerpt("Use parameter binding for SQL queries.")
                        .contentHash(hashService.sha256Hex("Use parameter binding for SQL queries."))
                        .metadata(Map.of("sourceDocumentId", "doc1", "chunkId", "chunk1"))
                        .build(),
                EvidenceDraft.builder()
                        .evidenceType("PROMPT")
                        .sourceName("PromptService")
                        .sourceRef("code-review-prompt")
                        .excerpt("Prompt text should stay task-level.")
                        .build()
        );

        List<ReviewEvidence> linked = service.attachContextEvidenceToFinding(task, finding, drafts);

        assertEquals(1, linked.size());
        assertEquals("RAG_SNIPPET", linked.get(0).getEvidenceType());
        assertEquals(10L, linked.get(0).getFindingId());
        assertEquals("review_context", linked.get(0).getMetadata().get("linkReason"));
        assertEquals("doc1", linked.get(0).getMetadata().get("sourceDocumentId"));
        assertTrue(Boolean.TRUE.equals(finding.getGrounded()));
        assertEquals(1, finding.getEvidenceCount());
        assertEquals(64, finding.getEvidenceHash().length());
        verify(findingRepository).save(finding);
    }

    @Test
    void should_reject_evidence_updates_at_entity_boundary() {
        ReviewEvidence evidence = ReviewEvidence.builder()
                .id(1L)
                .taskId(1L)
                .evidenceType("SOURCE_CODE")
                .excerpt("1: String password = request.getParameter(\"password\");")
                .contentHash("a".repeat(64))
                .build();

        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class, evidence::preventUpdate);

        assertTrue(error.getMessage().contains("append-only"));
    }

    @Test
    void should_mark_evidence_columns_as_not_updatable() throws Exception {
        for (String fieldName : List.of(
                "taskId",
                "findingId",
                "evidenceType",
                "sourceName",
                "sourceRef",
                "locator",
                "startLine",
                "endLine",
                "excerpt",
                "contentHash",
                "relevanceScore",
                "metadata",
                "createdAt"
        )) {
            Field field = ReviewEvidence.class.getDeclaredField(fieldName);
            Column column = field.getAnnotation(Column.class);
            assertNotNull(column, fieldName + " should be a persistent column");
            assertFalse(column.updatable(), fieldName + " should be append-only");
        }
    }
}
