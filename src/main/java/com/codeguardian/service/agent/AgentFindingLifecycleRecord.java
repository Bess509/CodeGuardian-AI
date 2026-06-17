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
public class AgentFindingLifecycleRecord {
    private String findingId;
    private String draftId;
    private String proposedByRole;
    private AgentFindingStatus status;
    private String challengedByRole;
    private String resolvedByRole;
    private String resolutionReason;
    private String previousSeverity;
    private String resolvedSeverity;
    private List<String> evidenceRefs;
    private Finding finding;
}
