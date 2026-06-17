package com.codeguardian.service.agent;

import com.codeguardian.service.ai.context.ReviewContextHolder;
import com.codeguardian.service.provenance.EvidenceDraft;
import com.codeguardian.service.provenance.ProvenanceHashService;
import com.codeguardian.service.provenance.ReviewAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ReviewAgentAuditService {

    private static final int EVIDENCE_MAX_CHARS = 4000;

    private final ReviewAuditService auditService;
    private final ProvenanceHashService hashService;

    public void record(ReviewAgentState state,
                       ReviewAgentWorkflow event,
                       String role,
                       int iteration,
                       int maxIterations,
                       String message,
                       Map<String, Object> extra) {
        if (state == null || state.getTaskId() == null || auditService == null) {
            return;
        }
        Map<String, Object> metadata = baseMetadata(state, role, event.name(), iteration, maxIterations);
        if (extra != null) {
            metadata.putAll(extra);
        }
        auditService.record(state.getTaskId(), event.name(), "AGENT", "system", message, metadata);
    }

    public void record(AgentContextView context,
                       ReviewAgentWorkflow event,
                       String role,
                       int iteration,
                       int maxIterations,
                       String message,
                       Map<String, Object> extra) {
        if (context == null || context.getTaskId() == null || auditService == null) {
            return;
        }
        Map<String, Object> metadata = baseMetadata(context, role, event.name(), iteration, maxIterations);
        if (extra != null) {
            metadata.putAll(extra);
        }
        auditService.record(context.getTaskId(), event.name(), "AGENT", "system", message, metadata);
    }

    public void addEvidence(ReviewAgentState state,
                            String evidenceType,
                            String sourceName,
                            String sourceRef,
                            String locator,
                            String excerpt,
                            Double relevanceScore,
                            Map<String, Object> metadata) {
        Map<String, Object> safeMetadata = metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>();
        if (state != null) {
            safeMetadata.putIfAbsent("workflowRunId", state.getWorkflowRunId());
            safeMetadata.putIfAbsent("sourceRef", state.getSourceRef());
            safeMetadata.putIfAbsent("language", state.getLanguage());
        }
        String safeExcerpt = trim(excerpt, EVIDENCE_MAX_CHARS);
        ReviewContextHolder.addEvidence(EvidenceDraft.builder()
                .evidenceType(evidenceType)
                .sourceName(sourceName)
                .sourceRef(sourceRef)
                .locator(locator)
                .excerpt(safeExcerpt)
                .contentHash(safeHash(safeExcerpt))
                .relevanceScore(relevanceScore)
                .metadata(safeMetadata)
                .build());
    }

    public void addEvidence(AgentContextView context,
                            String evidenceType,
                            String sourceName,
                            String sourceRef,
                            String locator,
                            String excerpt,
                            Double relevanceScore,
                            Map<String, Object> metadata) {
        Map<String, Object> safeMetadata = metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>();
        if (context != null) {
            safeMetadata.putIfAbsent("workflowRunId", context.getWorkflowRunId());
            safeMetadata.putIfAbsent("sourceRef", context.getSourceRef());
            safeMetadata.putIfAbsent("language", context.getLanguage());
        }
        String safeExcerpt = trim(excerpt, EVIDENCE_MAX_CHARS);
        ReviewContextHolder.addEvidence(EvidenceDraft.builder()
                .evidenceType(evidenceType)
                .sourceName(sourceName)
                .sourceRef(sourceRef)
                .locator(locator)
                .excerpt(safeExcerpt)
                .contentHash(safeHash(safeExcerpt))
                .relevanceScore(relevanceScore)
                .metadata(safeMetadata)
                .build());
    }

    public Map<String, Object> baseMetadata(ReviewAgentState state,
                                            String role,
                                            String step,
                                            int iteration,
                                            int maxIterations) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (state != null) {
            put(metadata, "workflowRunId", state.getWorkflowRunId());
            put(metadata, "taskId", state.getTaskId());
            put(metadata, "sourceRef", state.getSourceRef());
            put(metadata, "language", state.getLanguage());
        }
        put(metadata, "role", role);
        put(metadata, "step", step);
        put(metadata, "iteration", iteration);
        put(metadata, "maxIterations", maxIterations);
        return metadata;
    }

    public Map<String, Object> baseMetadata(AgentContextView context,
                                            String role,
                                            String step,
                                            int iteration,
                                            int maxIterations) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (context != null) {
            put(metadata, "workflowRunId", context.getWorkflowRunId());
            put(metadata, "taskId", context.getTaskId());
            put(metadata, "sourceRef", context.getSourceRef());
            put(metadata, "language", context.getLanguage());
        }
        put(metadata, "role", role);
        put(metadata, "step", step);
        put(metadata, "iteration", iteration);
        put(metadata, "maxIterations", maxIterations);
        return metadata;
    }

    public String safeHash(String value) {
        try {
            return hashService.sha256Hex(value != null ? value : "");
        } catch (Exception ignored) {
            return Integer.toHexString((value != null ? value : "").hashCode());
        }
    }

    public String trim(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 18)) + "\n... [truncated]";
    }

    public Map<String, Object> meta(Object... keyValues) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (keyValues == null) {
            return metadata;
        }
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            put(metadata, keyValues[i], keyValues[i + 1]);
        }
        return metadata;
    }

    private void put(Map<String, Object> metadata, Object key, Object value) {
        if (key != null && value != null) {
            metadata.put(String.valueOf(key), value);
        }
    }
}
