package com.codeguardian.service.provenance;

import com.codeguardian.dto.ReviewAuditEventDTO;
import com.codeguardian.dto.ReviewEvidenceDTO;
import com.codeguardian.dto.ReviewGroundingPolicyDTO;
import com.codeguardian.dto.ReviewIntegrityDTO;
import com.codeguardian.dto.ReviewProofBundleDTO;
import com.codeguardian.dto.ReviewProofBundleVerificationDTO;
import com.codeguardian.dto.ReviewRuntimeManifestDTO;
import com.codeguardian.dto.ReviewTraceGraphDTO;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReviewTraceGraphServiceTest {

    @Test
    void should_build_trace_graph_from_proof_bundle() {
        ReviewProofBundleService proofBundleService = mock(ReviewProofBundleService.class);
        ReviewTraceGraphService service = new ReviewTraceGraphService(proofBundleService);
        ReviewProofBundleDTO bundle = proofBundle();
        when(proofBundleService.buildBundle(7L)).thenReturn(bundle);
        when(proofBundleService.verifyBundleAgainstCurrentState(7L, bundle)).thenReturn(ReviewProofBundleVerificationDTO.builder()
                .valid(true)
                .evidenceHashValid(true)
                .reviewStateHashValid(true)
                .runtimeManifestHashValid(true)
                .groundingPolicyValid(true)
                .auditOrderValid(true)
                .currentStateMatch(true)
                .currentReviewStateMatch(true)
                .currentRuntimeManifestMatch(true)
                .currentBundleMatch(true)
                .reason("ok")
                .build());

        ReviewTraceGraphDTO graph = service.buildGraph(7L);

        assertEquals(ReviewTraceGraphService.GRAPH_VERSION, graph.getGraphVersion());
        assertEquals(7L, graph.getTaskId());
        assertEquals("state-hash", graph.getReviewStateHash());
        assertEquals(9, graph.getSummary().getNodeCount());
        assertEquals(10, graph.getSummary().getEdgeCount());
        assertEquals(1, graph.getSummary().getFindingCount());
        assertEquals(1, graph.getSummary().getEvidenceCount());
        assertEquals(2, graph.getSummary().getAuditEventCount());
        assertTrue(Boolean.TRUE.equals(graph.getSummary().getAuditCoverageValid()));
        assertTrue(Boolean.TRUE.equals(graph.getSummary().getAuditOrderValid()));
        assertEquals(0, graph.getSummary().getAuditOrderViolationCount());
        assertTrue(Boolean.TRUE.equals(graph.getSummary().getProofBundleValid()));
        assertTrue(Boolean.TRUE.equals(graph.getSummary().getCurrentStateMatch()));
        assertTrue(Boolean.TRUE.equals(graph.getSummary().getDatabaseAppendOnlyGuardsInstalled()));
        assertTrue(graph.getNodes().stream().anyMatch(node -> "database-append-only-guards".equals(node.getId())
                && "DATABASE_GUARD".equals(node.getType())
                && "INSTALLED".equals(node.getStatus())));
        assertTrue(graph.getEdges().stream().anyMatch(edge -> "runtime-manifest".equals(edge.getSource())
                && "database-append-only-guards".equals(edge.getTarget())
                && "DECLARES_GUARD".equals(edge.getType())));
        assertTrue(graph.getNodes().stream().anyMatch(node -> "finding:3".equals(node.getId())
                && "FINDING".equals(node.getType())
                && "GROUNDED".equals(node.getStatus())));
        assertTrue(graph.getEdges().stream().anyMatch(edge -> "finding:3".equals(edge.getSource())
                && "evidence:11".equals(edge.getTarget())
                && "GROUNDED_BY".equals(edge.getType())));
        assertTrue(graph.getEdges().stream().anyMatch(edge -> "audit:1".equals(edge.getSource())
                && "audit:2".equals(edge.getTarget())
                && "NEXT_AUDIT_EVENT".equals(edge.getType())));
        assertTrue(graph.getNodes().stream().noneMatch(node -> "evidence:12".equals(node.getId())));
        assertTrue(graph.getEdges().stream().noneMatch(edge -> "edge:task-evidence-12".equals(edge.getId())));
    }

    @Test
    void should_render_grounding_policy_violations_as_graph_nodes() {
        ReviewProofBundleService proofBundleService = mock(ReviewProofBundleService.class);
        ReviewTraceGraphService service = new ReviewTraceGraphService(proofBundleService);
        ReviewProofBundleDTO bundle = proofBundle();
        bundle.getGroundingPolicy().setValid(false);
        bundle.getGroundingPolicy().setReason("grounding_policy_failed");
        bundle.getGroundingPolicy().setViolationCount(1);
        bundle.getGroundingPolicy().setInvalidSourceAnchorCount(1);
        bundle.getGroundingPolicy().setViolations(List.of(ReviewGroundingPolicyDTO.Violation.builder()
                .findingId(3L)
                .severity(0)
                .severityLabel("CRITICAL")
                .title("SQL injection")
                .location("PaymentService.java:12")
                .reasons(List.of("source_anchor_invalid"))
                .evidenceTypes(List.of("SOURCE_CODE"))
                .expectedEvidenceCount(1)
                .providedEvidenceCount(1)
                .expectedEvidenceHash("expected-hash")
                .providedEvidenceHash("provided-hash")
                .invalidSourceEvidenceIds(List.of(11L))
                .build()));
        bundle.getCounts().setGroundingViolationCount(1);

        when(proofBundleService.buildBundle(7L)).thenReturn(bundle);
        when(proofBundleService.verifyBundleAgainstCurrentState(7L, bundle)).thenReturn(ReviewProofBundleVerificationDTO.builder()
                .valid(false)
                .evidenceHashValid(true)
                .reviewStateHashValid(true)
                .runtimeManifestHashValid(true)
                .groundingPolicyValid(false)
                .auditOrderValid(true)
                .currentStateMatch(true)
                .currentReviewStateMatch(true)
                .currentRuntimeManifestMatch(true)
                .currentBundleMatch(true)
                .reason("grounding_policy_failed")
                .build());

        ReviewTraceGraphDTO graph = service.buildGraph(7L);

        assertEquals(10, graph.getSummary().getNodeCount());
        assertEquals(12, graph.getSummary().getEdgeCount());
        assertEquals(1, graph.getSummary().getGroundingViolationNodeCount());
        assertEquals(1, graph.getSummary().getInvalidSourceAnchorCount());
        assertTrue(graph.getNodes().stream().anyMatch(node -> "grounding-violation:3".equals(node.getId())
                && "GROUNDING_VIOLATION".equals(node.getType())
                && "BLOCKED".equals(node.getStatus())
                && List.of("source_anchor_invalid").equals(node.getMetadata().get("reasons"))));
        assertTrue(graph.getEdges().stream().anyMatch(edge -> "grounding-policy".equals(edge.getSource())
                && "grounding-violation:3".equals(edge.getTarget())
                && "HAS_VIOLATION".equals(edge.getType())));
        assertTrue(graph.getEdges().stream().anyMatch(edge -> "grounding-violation:3".equals(edge.getSource())
                && "finding:3".equals(edge.getTarget())
                && "BLOCKS_FINDING".equals(edge.getType())));
    }

    private ReviewProofBundleDTO proofBundle() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 9, 10, 0);
        return ReviewProofBundleDTO.builder()
                .schemaVersion(ReviewProofBundleService.SCHEMA_VERSION)
                .reviewStateHash("state-hash")
                .bundleHash("bundle-hash")
                .task(ReviewProofBundleDTO.TaskSnapshot.builder()
                        .id(7L)
                        .name("payment review")
                        .reviewTypeLabel("SNIPPET")
                        .scopeHash("scope-hash")
                        .statusLabel("COMPLETED")
                        .createdAt(now)
                        .completedAt(now.plusMinutes(1))
                        .build())
                .integrity(ReviewIntegrityDTO.builder()
                        .auditChainValid(true)
                        .auditCoverageValid(true)
                        .auditOrderValid(true)
                        .auditOrderViolations(List.of())
                        .build())
                .runtimeManifest(ReviewRuntimeManifestDTO.builder()
                        .manifestHash("manifest-hash")
                        .ai(ReviewRuntimeManifestDTO.AiSnapshot.builder().provider("OPENAI").build())
                        .rag(ReviewRuntimeManifestDTO.RagSnapshot.builder().retrievalStrategy("VECTOR_THEN_HYBRID_FALLBACK").build())
                        .audit(ReviewRuntimeManifestDTO.AuditSnapshot.builder().signingActive(true).build())
                        .databaseGuards(ReviewRuntimeManifestDTO.DatabaseGuardSnapshot.builder()
                                .guardVersion("codeguardian-db-append-only-guards-v1")
                                .querySupported(true)
                                .appendOnlyGuardsInstalled(true)
                                .protectedTables(List.of("review_audit_events", "review_evidence"))
                                .expectedTriggerCount(2)
                                .installedTriggerCount(2)
                                .updatesBlocked(true)
                                .deletesBlocked(true)
                                .verificationReason("ok")
                                .build())
                        .build())
                .groundingPolicy(ReviewGroundingPolicyDTO.builder()
                        .policyVersion("policy-v1")
                        .minSeverity("HIGH")
                        .requiredEvidenceTypes(List.of("SOURCE_CODE"))
                        .valid(true)
                        .violationCount(0)
                        .build())
                .counts(ReviewProofBundleDTO.Counts.builder()
                        .auditEventCount(2)
                        .evidenceCount(2)
                        .findingCount(1)
                        .groundedFindingCount(1L)
                        .groundingViolationCount(0)
                        .build())
                .auditEvents(List.of(
                        ReviewAuditEventDTO.builder()
                                .id(1L)
                                .taskId(7L)
                                .eventType("TASK_STARTED")
                                .stage("ORCHESTRATION")
                                .eventHash("audit-hash-1")
                                .previousHash("0".repeat(64))
                                .metadata(Map.of())
                                .createdAt(now)
                                .build(),
                        ReviewAuditEventDTO.builder()
                                .id(2L)
                                .taskId(7L)
                                .eventType("TASK_COMPLETED")
                                .stage("ORCHESTRATION")
                                .eventHash("audit-hash-2")
                                .previousHash("audit-hash-1")
                                .metadata(Map.of())
                                .createdAt(now.plusSeconds(5))
                                .build()
                ))
                .findings(List.of(ReviewProofBundleDTO.FindingSnapshot.builder()
                        .id(3L)
                        .taskId(7L)
                        .severityLabel("CRITICAL")
                        .title("SQL injection")
                        .location("PaymentService.java:12")
                        .grounded(true)
                        .evidenceCount(1)
                        .evidenceHash("finding-evidence-hash")
                        .build()))
                .evidence(List.of(
                        ReviewEvidenceDTO.builder()
                                .id(11L)
                                .taskId(7L)
                                .findingId(3L)
                                .evidenceType("SOURCE_CODE")
                                .sourceName("PaymentService.java")
                                .sourceRef("repo://PaymentService.java")
                                .locator("PaymentService.java:12")
                                .contentHash("source-content-hash")
                                .build(),
                        ReviewEvidenceDTO.builder()
                                .id(12L)
                                .taskId(7L)
                                .evidenceType("RAG_SNIPPET")
                                .sourceName("KnowledgeBaseService")
                                .sourceRef("knowledge://rules#chunk=sql")
                                .contentHash("rag-content-hash")
                                .build()
                ))
                .build();
    }
}
