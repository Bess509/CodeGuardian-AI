package com.codeguardian.service.provenance;

import static com.codeguardian.service.provenance.ReviewTraceGraphSupport.*;

import com.codeguardian.dto.ReviewAuditEventDTO;
import com.codeguardian.dto.ReviewEvidenceDTO;
import com.codeguardian.dto.ReviewGroundingPolicyDTO;
import com.codeguardian.dto.ReviewProofBundleDTO;
import com.codeguardian.dto.ReviewProofBundleVerificationDTO;
import com.codeguardian.dto.ReviewRuntimeManifestDTO;
import com.codeguardian.dto.ReviewTraceGraphDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReviewTraceGraphService {

    public static final String GRAPH_VERSION = "codeguardian-review-trace-graph-v1";

    private final ReviewProofBundleService proofBundleService;

    public ReviewTraceGraphDTO buildGraph(Long taskId) {
        ReviewProofBundleDTO bundle = proofBundleService.buildBundle(taskId);
        ReviewProofBundleVerificationDTO verification = proofBundleService.verifyBundleAgainstCurrentState(taskId, bundle);
        List<ReviewTraceGraphDTO.Node> nodes = new ArrayList<>();
        List<ReviewTraceGraphDTO.Edge> edges = new ArrayList<>();

        addTaskNode(bundle, nodes);
        addProofNode(bundle, verification, nodes, edges);
        addRuntimeManifestNode(bundle.getRuntimeManifest(), verification, nodes, edges);
        addDatabaseGuardNode(bundle.getRuntimeManifest(), nodes, edges);
        addGroundingPolicyNode(bundle.getGroundingPolicy(), verification, nodes, edges);
        addAuditNodes(bundle, nodes, edges);
        addFindingAndEvidenceNodes(bundle, nodes, edges);
        addGroundingViolationNodes(bundle.getGroundingPolicy(), nodes, edges);

        ReviewGroundingPolicyDTO groundingPolicy = bundle.getGroundingPolicy();
        int visibleEvidenceCount = visibleGraphEvidence(bundle.getEvidence()).size();
        ReviewTraceGraphDTO.Summary summary = ReviewTraceGraphDTO.Summary.builder()
                .nodeCount(nodes.size())
                .edgeCount(edges.size())
                .findingCount(size(bundle.getFindings()))
                .evidenceCount(visibleEvidenceCount)
                .auditEventCount(size(bundle.getAuditEvents()))
                .groundedFindingCount(bundle.getCounts() != null && bundle.getCounts().getGroundedFindingCount() != null
                        ? bundle.getCounts().getGroundedFindingCount().intValue() : 0)
                .groundingViolationCount(bundle.getCounts() != null ? bundle.getCounts().getGroundingViolationCount() : 0)
                .groundingViolationNodeCount(groundingPolicy != null ? size(groundingPolicy.getViolations()) : 0)
                .invalidSourceAnchorCount(groundingPolicy != null && groundingPolicy.getInvalidSourceAnchorCount() != null
                        ? groundingPolicy.getInvalidSourceAnchorCount() : 0)
                .auditChainValid(bundle.getIntegrity() != null ? bundle.getIntegrity().getAuditChainValid() : false)
                .auditCoverageValid(bundle.getIntegrity() != null ? bundle.getIntegrity().getAuditCoverageValid() : false)
                .auditOrderValid(bundle.getIntegrity() != null ? bundle.getIntegrity().getAuditOrderValid() : false)
                .auditOrderViolationCount(bundle.getIntegrity() != null && bundle.getIntegrity().getAuditOrderViolations() != null
                        ? bundle.getIntegrity().getAuditOrderViolations().size() : 0)
                .evidenceHashValid(verification.getEvidenceHashValid())
                .reviewStateHashValid(verification.getReviewStateHashValid())
                .runtimeManifestHashValid(verification.getRuntimeManifestHashValid())
                .groundingPolicyValid(verification.getGroundingPolicyValid())
                .proofBundleValid(verification.getValid())
                .currentStateMatch(verification.getCurrentStateMatch())
                .currentReviewStateMatch(verification.getCurrentReviewStateMatch())
                .currentRuntimeManifestMatch(verification.getCurrentRuntimeManifestMatch())
                .currentBundleMatch(verification.getCurrentBundleMatch())
                .databaseAppendOnlyGuardsInstalled(databaseAppendOnlyGuardsInstalled(bundle.getRuntimeManifest()))
                .build();

        return ReviewTraceGraphDTO.builder()
                .graphVersion(GRAPH_VERSION)
                .taskId(taskId)
                .reviewStateHash(bundle.getReviewStateHash())
                .bundleHash(bundle.getBundleHash())
                .summary(summary)
                .nodes(nodes)
                .edges(edges)
                .build();
    }

    private void addTaskNode(ReviewProofBundleDTO bundle, List<ReviewTraceGraphDTO.Node> nodes) {
        ReviewProofBundleDTO.TaskSnapshot task = bundle.getTask();
        if (task == null) {
            return;
        }
        nodes.add(node(
                taskId(task.getId()),
                "TASK",
                nonBlank(task.getName(), "Task " + task.getId()),
                task.getStatusLabel(),
                task.getScopeHash(),
                mapOf(
                        "reviewType", task.getReviewTypeLabel(),
                        "scopeHash", task.getScopeHash(),
                        "createdAt", task.getCreatedAt(),
                        "completedAt", task.getCompletedAt()
                )
        ));
    }

    private void addProofNode(ReviewProofBundleDTO bundle,
                              ReviewProofBundleVerificationDTO verification,
                              List<ReviewTraceGraphDTO.Node> nodes,
                              List<ReviewTraceGraphDTO.Edge> edges) {
        String proofId = "proof-bundle";
        nodes.add(node(
                proofId,
                "PROOF_BUNDLE",
                "Proof bundle",
                Boolean.TRUE.equals(verification.getValid()) ? "VALID" : "BROKEN",
                bundle.getBundleHash(),
                mapOf(
                        "schemaVersion", bundle.getSchemaVersion(),
                        "reviewStateHash", bundle.getReviewStateHash(),
                        "signatureKeyId", bundle.getBundleSignatureKeyId(),
                        "reason", verification.getReason()
                )
        ));
        if (bundle.getTask() != null) {
            edges.add(edge("edge:proof-task", proofId, taskId(bundle.getTask().getId()), "PROVES", "proves", Map.of()));
        }
    }

    private void addRuntimeManifestNode(ReviewRuntimeManifestDTO manifest,
                                        ReviewProofBundleVerificationDTO verification,
                                        List<ReviewTraceGraphDTO.Node> nodes,
                                        List<ReviewTraceGraphDTO.Edge> edges) {
        if (manifest == null) {
            return;
        }
        nodes.add(node(
                "runtime-manifest",
                "RUNTIME_MANIFEST",
                "Runtime manifest",
                Boolean.TRUE.equals(verification.getRuntimeManifestHashValid()) ? "VALID" : "BROKEN",
                manifest.getManifestHash(),
                mapOf(
                        "aiProvider", manifest.getAi() != null ? manifest.getAi().getProvider() : null,
                        "ragStrategy", manifest.getRag() != null ? manifest.getRag().getRetrievalStrategy() : null,
                        "auditSigningActive", manifest.getAudit() != null ? manifest.getAudit().getSigningActive() : null
                )
        ));
        edges.add(edge("edge:proof-runtime-manifest", "proof-bundle", "runtime-manifest",
                "CAPTURED_UNDER", "runtime", Map.of()));
    }

    private void addDatabaseGuardNode(ReviewRuntimeManifestDTO manifest,
                                      List<ReviewTraceGraphDTO.Node> nodes,
                                      List<ReviewTraceGraphDTO.Edge> edges) {
        if (manifest == null || manifest.getDatabaseGuards() == null) {
            return;
        }
        ReviewRuntimeManifestDTO.DatabaseGuardSnapshot guard = manifest.getDatabaseGuards();
        Boolean installed = guard.getAppendOnlyGuardsInstalled();
        String status = installed == null
                ? "UNKNOWN"
                : Boolean.TRUE.equals(installed) ? "INSTALLED" : "MISSING";
        nodes.add(node(
                "database-append-only-guards",
                "DATABASE_GUARD",
                "DB append-only guards",
                status,
                guard.getGuardVersion(),
                mapOf(
                        "guardVersion", guard.getGuardVersion(),
                        "querySupported", guard.getQuerySupported(),
                        "appendOnlyGuardsInstalled", guard.getAppendOnlyGuardsInstalled(),
                        "protectedTables", guard.getProtectedTables(),
                        "expectedTriggerCount", guard.getExpectedTriggerCount(),
                        "installedTriggerCount", guard.getInstalledTriggerCount(),
                        "updatesBlocked", guard.getUpdatesBlocked(),
                        "deletesBlocked", guard.getDeletesBlocked(),
                        "verificationReason", guard.getVerificationReason()
                )
        ));
        edges.add(edge("edge:runtime-manifest-database-guards", "runtime-manifest",
                "database-append-only-guards", "DECLARES_GUARD", "db guard", Map.of()));
        edges.add(edge("edge:proof-database-guard", "proof-bundle",
                "database-append-only-guards", "REQUIRES_RUNTIME_GUARD", "requires guard", Map.of()));
    }

    private void addGroundingPolicyNode(ReviewGroundingPolicyDTO policy,
                                        ReviewProofBundleVerificationDTO verification,
                                        List<ReviewTraceGraphDTO.Node> nodes,
                                        List<ReviewTraceGraphDTO.Edge> edges) {
        if (policy == null) {
            return;
        }
        nodes.add(node(
                "grounding-policy",
                "GROUNDING_POLICY",
                "Grounding policy",
                Boolean.TRUE.equals(verification.getGroundingPolicyValid()) ? "VALID" : "BLOCKED",
                null,
                mapOf(
                        "policyVersion", policy.getPolicyVersion(),
                        "minSeverity", policy.getMinSeverity(),
                        "requiredEvidenceTypes", policy.getRequiredEvidenceTypes(),
                        "violationCount", policy.getViolationCount(),
                        "invalidSourceAnchorCount", policy.getInvalidSourceAnchorCount()
                )
        ));
        edges.add(edge("edge:proof-grounding-policy", "proof-bundle", "grounding-policy",
                "EVALUATED_BY", "policy", Map.of()));
    }

    private void addGroundingViolationNodes(ReviewGroundingPolicyDTO policy,
                                            List<ReviewTraceGraphDTO.Node> nodes,
                                            List<ReviewTraceGraphDTO.Edge> edges) {
        if (policy == null || policy.getViolations() == null || policy.getViolations().isEmpty()) {
            return;
        }
        int index = 0;
        for (ReviewGroundingPolicyDTO.Violation violation : policy.getViolations()) {
            index++;
            String violationId = violationNodeId(violation.getFindingId(), index);
            nodes.add(node(
                    violationId,
                    "GROUNDING_VIOLATION",
                    violation.getFindingId() != null
                            ? "Grounding violation for finding " + violation.getFindingId()
                            : "Grounding violation " + index,
                    "BLOCKED",
                    violation.getExpectedEvidenceHash(),
                    mapOf(
                            "findingId", violation.getFindingId(),
                            "severity", violation.getSeverityLabel(),
                            "location", violation.getLocation(),
                            "reasons", violation.getReasons(),
                            "evidenceTypes", violation.getEvidenceTypes(),
                            "expectedEvidenceCount", violation.getExpectedEvidenceCount(),
                            "providedEvidenceCount", violation.getProvidedEvidenceCount(),
                            "expectedEvidenceHash", violation.getExpectedEvidenceHash(),
                            "providedEvidenceHash", violation.getProvidedEvidenceHash(),
                            "invalidSourceEvidenceIds", violation.getInvalidSourceEvidenceIds()
                    )
            ));
            edges.add(edge("edge:policy-violation-" + index, "grounding-policy", violationId,
                    "HAS_VIOLATION", "violation", Map.of()));
            if (violation.getFindingId() != null) {
                edges.add(edge("edge:violation-finding-" + violation.getFindingId(), violationId,
                        findingNodeId(violation.getFindingId()), "BLOCKS_FINDING", "blocks",
                        Map.of("reasons", violation.getReasons() != null ? violation.getReasons() : List.of())));
            }
        }
    }

    private void addAuditNodes(ReviewProofBundleDTO bundle,
                               List<ReviewTraceGraphDTO.Node> nodes,
                               List<ReviewTraceGraphDTO.Edge> edges) {
        List<ReviewAuditEventDTO> auditEvents = bundle.getAuditEvents() != null ? bundle.getAuditEvents() : List.of();
        ReviewAuditEventDTO previous = null;
        for (ReviewAuditEventDTO event : auditEvents) {
            String eventId = auditNodeId(event.getId());
            nodes.add(node(
                    eventId,
                    "AUDIT_EVENT",
                    nonBlank(event.getEventType(), "Audit event " + event.getId()),
                    event.getEventSignature() != null && !event.getEventSignature().isBlank() ? "SIGNED" : "UNSIGNED",
                    event.getEventHash(),
                    mapOf(
                            "stage", event.getStage(),
                            "actor", event.getActor(),
                            "payloadHash", event.getPayloadHash(),
                            "previousHash", event.getPreviousHash(),
                            "createdAt", event.getCreatedAt()
                    )
            ));
            edges.add(edge("edge:task-audit-" + event.getId(), taskId(event.getTaskId()), eventId,
                    "HAS_AUDIT_EVENT", "audit", Map.of("stage", nullToEmpty(event.getStage()))));
            if (previous != null) {
                edges.add(edge("edge:audit-chain-" + previous.getId() + "-" + event.getId(),
                        auditNodeId(previous.getId()), eventId, "NEXT_AUDIT_EVENT", "next",
                        Map.of("previousHash", nullToEmpty(event.getPreviousHash()))));
            }
            previous = event;
        }
    }

    private void addFindingAndEvidenceNodes(ReviewProofBundleDTO bundle,
                                            List<ReviewTraceGraphDTO.Node> nodes,
                                            List<ReviewTraceGraphDTO.Edge> edges) {
        List<ReviewEvidenceDTO> evidence = visibleGraphEvidence(bundle.getEvidence());
        Map<Long, List<ReviewEvidenceDTO>> evidenceByFinding = evidence.stream()
                .filter(item -> item.getFindingId() != null)
                .collect(Collectors.groupingBy(ReviewEvidenceDTO::getFindingId));
        Long taskId = bundle.getTask() != null ? bundle.getTask().getId() : null;

        for (ReviewProofBundleDTO.FindingSnapshot finding : sortedFindings(bundle.getFindings())) {
            String findingId = findingNodeId(finding.getId());
            nodes.add(node(
                    findingId,
                    "FINDING",
                    nonBlank(finding.getTitle(), "Finding " + finding.getId()),
                    Boolean.TRUE.equals(finding.getGrounded()) ? "GROUNDED" : "UNGROUNDED",
                    finding.getEvidenceHash(),
                    mapOf(
                            "severity", finding.getSeverityLabel(),
                            "location", finding.getLocation(),
                            "source", finding.getSource(),
                            "confidence", finding.getConfidence(),
                            "evidenceCount", finding.getEvidenceCount()
                    )
            ));
            if (taskId != null) {
                edges.add(edge("edge:task-finding-" + finding.getId(), taskId(taskId), findingId,
                        "HAS_FINDING", "finding", Map.of("severity", nullToEmpty(finding.getSeverityLabel()))));
            }
            for (ReviewEvidenceDTO item : evidenceByFinding.getOrDefault(finding.getId(), List.of())) {
                addEvidenceNode(item, nodes);
                edges.add(edge("edge:finding-evidence-" + finding.getId() + "-" + item.getId(),
                        findingId, evidenceNodeId(item.getId()), "GROUNDED_BY", "evidence",
                        Map.of("evidenceType", nullToEmpty(item.getEvidenceType()))));
            }
        }

        for (ReviewEvidenceDTO item : evidence) {
            if (item.getFindingId() != null) {
                continue;
            }
            addEvidenceNode(item, nodes);
            if (taskId != null) {
                edges.add(edge("edge:task-evidence-" + item.getId(), taskId(taskId), evidenceNodeId(item.getId()),
                        "HAS_TASK_EVIDENCE", "task evidence", Map.of("evidenceType", nullToEmpty(item.getEvidenceType()))));
            }
        }
    }

    private void addEvidenceNode(ReviewEvidenceDTO evidence, List<ReviewTraceGraphDTO.Node> nodes) {
        String id = evidenceNodeId(evidence.getId());
        boolean exists = nodes.stream().anyMatch(node -> Objects.equals(node.getId(), id));
        if (exists) {
            return;
        }
        nodes.add(node(
                id,
                "EVIDENCE",
                nonBlank(evidence.getEvidenceType(), "Evidence " + evidence.getId()),
                evidence.getContentHash() != null && !evidence.getContentHash().isBlank() ? "HASHED" : "UNHASHED",
                evidence.getContentHash(),
                mapOf(
                        "sourceName", evidence.getSourceName(),
                        "sourceRef", evidence.getSourceRef(),
                        "locator", evidence.getLocator(),
                        "startLine", evidence.getStartLine(),
                        "endLine", evidence.getEndLine(),
                        "relevanceScore", evidence.getRelevanceScore()
                )
        ));
    }

    private List<ReviewEvidenceDTO> visibleGraphEvidence(List<ReviewEvidenceDTO> evidence) {
        if (evidence == null) {
            return List.of();
        }
        return evidence.stream()
                .filter(item -> !isTaskLevelRagSnippet(item))
                .toList();
    }

    private boolean isTaskLevelRagSnippet(ReviewEvidenceDTO evidence) {
        return evidence != null
                && evidence.getFindingId() == null
                && isTaskLevelRagEvidenceType(evidence.getEvidenceType());
    }

    private boolean isTaskLevelRagEvidenceType(String evidenceType) {
        return "RAG_SNIPPET".equals(evidenceType)
                || "TASK_RAG_PACK".equals(evidenceType)
                || "TASK_RAG_PACK_USED".equals(evidenceType);
    }

    private List<ReviewProofBundleDTO.FindingSnapshot> sortedFindings(List<ReviewProofBundleDTO.FindingSnapshot> findings) {
        if (findings == null) {
            return List.of();
        }
        return findings.stream()
                .sorted(Comparator.comparing(ReviewProofBundleDTO.FindingSnapshot::getId,
                        Comparator.nullsLast(Long::compareTo)))
                .toList();
    }

}
