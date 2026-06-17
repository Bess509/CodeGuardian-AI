package com.codeguardian.service.agent;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class VerifierAgentContext implements AgentContextView {
    String workflowRunId;
    Long taskId;
    String sourceRef;
    String language;
    String code;

    public static VerifierAgentContext from(ReviewAgentState state) {
        return VerifierAgentContext.builder()
                .workflowRunId(state.getWorkflowRunId())
                .taskId(state.getTaskId())
                .sourceRef(state.getSourceRef())
                .language(state.getLanguage())
                .code(state.getCode())
                .build();
    }

    public int lineCount() {
        if (code == null || code.isEmpty()) {
            return 0;
        }
        return code.split("\\R", -1).length;
    }
}
