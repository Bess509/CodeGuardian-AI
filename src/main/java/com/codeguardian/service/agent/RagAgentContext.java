package com.codeguardian.service.agent;

import com.codeguardian.entity.Finding;
import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class RagAgentContext implements AgentContextView {
    String workflowRunId;
    Long taskId;
    String sourceRef;
    String language;
    String code;
    List<Finding> seedFindings;

    public static RagAgentContext from(ReviewAgentState state) {
        return RagAgentContext.builder()
                .workflowRunId(state.getWorkflowRunId())
                .taskId(state.getTaskId())
                .sourceRef(state.getSourceRef())
                .language(state.getLanguage())
                .code(state.getCode())
                .seedFindings(state.getSeedFindings() != null ? List.copyOf(state.getSeedFindings()) : List.of())
                .build();
    }
}
