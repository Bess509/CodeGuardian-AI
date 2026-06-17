package com.codeguardian.service.agent;

import com.codeguardian.dto.ReviewRequestDTO;
import com.codeguardian.entity.Finding;
import com.codeguardian.service.rag.RetrievedKnowledgeChunk;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewAgentState {
    private String workflowRunId;
    private Long taskId;
    private String sourceRef;
    private String language;
    private String code;
    private ReviewRequestDTO request;
    private List<Finding> seedFindings;
    private String planMarkdown;
    private List<RetrievedKnowledgeChunk> ragChunks;
    private List<AgentCandidateFinding> draftFindings;
    private List<AgentFindingLifecycleRecord> lifecycleRecords;
    private List<JudgeDecision> judgeDecisions;
    private List<Finding> finalFindings;

    public int lineCount() {
        if (code == null || code.isEmpty()) {
            return 0;
        }
        return code.split("\\R", -1).length;
    }
}
