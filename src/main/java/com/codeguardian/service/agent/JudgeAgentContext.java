package com.codeguardian.service.agent;

import com.codeguardian.dto.ReviewRequestDTO;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class JudgeAgentContext implements AgentContextView {
    String workflowRunId;
    Long taskId;
    String sourceRef;
    String language;
    String code;
    ReviewRequestDTO request;
    String planMarkdown;

    public static JudgeAgentContext from(ReviewAgentState state) {
        return JudgeAgentContext.builder()
                .workflowRunId(state.getWorkflowRunId())
                .taskId(state.getTaskId())
                .sourceRef(state.getSourceRef())
                .language(state.getLanguage())
                .code(state.getCode())
                .request(state.getRequest())
                .planMarkdown(state.getPlanMarkdown())
                .build();
    }
}
