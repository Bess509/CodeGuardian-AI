package com.codeguardian.service.agent;

import com.codeguardian.entity.Finding;
import com.codeguardian.service.provenance.EvidenceDraft;
import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class AgentReviewResult {
    List<Finding> findings;
    List<EvidenceDraft> evidenceDrafts;

    public static AgentReviewResult of(List<Finding> findings, List<EvidenceDraft> evidenceDrafts) {
        return AgentReviewResult.builder()
                .findings(findings != null ? List.copyOf(findings) : List.of())
                .evidenceDrafts(evidenceDrafts != null ? List.copyOf(evidenceDrafts) : List.of())
                .build();
    }
}
