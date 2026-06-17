package com.codeguardian.service.agent;

import com.codeguardian.entity.Finding;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentCandidateFinding {
    private String draftId;
    private String proposedByRole;
    private Finding finding;
    private List<String> ragEvidenceRefs;
    private String sourceEvidenceHint;
}
