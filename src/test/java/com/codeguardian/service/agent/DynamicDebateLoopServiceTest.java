package com.codeguardian.service.agent;

import com.codeguardian.entity.Finding;
import com.codeguardian.enums.SeverityEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

class DynamicDebateLoopServiceTest {

    private final ReviewAgentAuditService auditService = mock(ReviewAgentAuditService.class);
    private final DynamicDebateLoopService service = new DynamicDebateLoopService(auditService, new ObjectMapper());

    @Test
    void should_merge_duplicate_candidates() {
        AgentCandidateFinding first = candidate("a", "src/App.java", 10, "SECURITY", "Secret leak", SeverityEnum.HIGH);
        AgentCandidateFinding duplicate = candidate("b", "src/App.java", 10, "SECURITY", "Secret leak", SeverityEnum.HIGH);

        List<AgentCandidateFinding> result = service.run(state(), List.of(first, duplicate), 3);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getDraftId()).isEqualTo("a");
    }

    @Test
    void should_remove_candidates_without_line_anchor() {
        AgentCandidateFinding weak = candidate("weak", "src/App.java", null, "CORRECTNESS", "No line", SeverityEnum.MEDIUM);
        AgentCandidateFinding strong = candidate("strong", "src/App.java", 12, "CORRECTNESS", "Has line", SeverityEnum.MEDIUM);

        List<AgentCandidateFinding> result = service.run(state(), List.of(weak, strong), 3);

        assertThat(result).extracting(AgentCandidateFinding::getDraftId).containsExactly("strong");
    }

    @Test
    void should_mark_high_risk_candidates_that_need_more_evidence_once() {
        AgentCandidateFinding high = candidate("high", "src/App.java", 12, "SECURITY", "Secret leak", SeverityEnum.HIGH);

        List<AgentCandidateFinding> result = service.run(state(), List.of(high), 3);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getFinding().getGroundingSummary())
                .contains("Debate loop requested more evidence");
    }

    private ReviewAgentState state() {
        return ReviewAgentState.builder()
                .workflowRunId("run-1")
                .taskId(1L)
                .sourceRef("src/App.java")
                .language("Java")
                .build();
    }

    private AgentCandidateFinding candidate(String id,
                                            String location,
                                            Integer line,
                                            String category,
                                            String title,
                                            SeverityEnum severity) {
        return AgentCandidateFinding.builder()
                .draftId(id)
                .finding(Finding.builder()
                        .location(location)
                        .startLine(line)
                        .endLine(line)
                        .category(category)
                        .title(title)
                        .severity(severity.getValue())
                        .build())
                .ragEvidenceRefs(List.of())
                .build();
    }
}
