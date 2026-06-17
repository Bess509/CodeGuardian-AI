package com.codeguardian.service.agent;

interface AgentContextView {
    String getWorkflowRunId();

    Long getTaskId();

    String getSourceRef();

    String getLanguage();
}
