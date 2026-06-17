package com.codeguardian.service.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JudgeDecision {
    private String draftId;
    private String decision;
    private String suggestedSeverity;
    private String reason;
    private List<String> requiredEvidenceTypes;
}
