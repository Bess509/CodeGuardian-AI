package com.codeguardian.service.agent;

import com.codeguardian.dto.ReviewRequestDTO;
import com.codeguardian.entity.Finding;
import com.codeguardian.service.rag.RetrievedKnowledgeChunk;
import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class ReviewerAgentContext implements AgentContextView {
    String workflowRunId;
    Long taskId;
    String sourceRef;
    String language;
    String code;
    ReviewRequestDTO request;
    List<Finding> seedFindings;
    String planMarkdown;
    List<RetrievedKnowledgeChunk> ragChunks;

    public static ReviewerAgentContext from(ReviewAgentState state) {
        return ReviewerAgentContext.builder()
                .workflowRunId(state.getWorkflowRunId())
                .taskId(state.getTaskId())
                .sourceRef(state.getSourceRef())
                .language(state.getLanguage())
                .code(state.getCode())
                .request(state.getRequest())
                .seedFindings(copy(state.getSeedFindings()))
                .planMarkdown(state.getPlanMarkdown())
                .ragChunks(copy(state.getRagChunks()))
                .build();
    }

    private static <T> List<T> copy(List<T> values) {
        return values != null ? List.copyOf(values) : List.of();
    }
}
