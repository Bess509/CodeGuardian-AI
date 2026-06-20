package com.codeguardian.service.session;

import com.codeguardian.entity.ReviewSession;
import com.codeguardian.entity.ReviewSessionMemory;
import com.codeguardian.entity.ReviewSessionMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationContextAssemblerTest {

    private final ConversationContextAssembler assembler =
            new ConversationContextAssembler(null, 20, Duration.ofMinutes(20));

    @Test
    void assemblePrioritizesCurrentQuestionTaskEvidenceRecentMessagesSummaryAndMemories() {
        ReviewSession session = ReviewSession.builder()
                .id(10L)
                .summary("Session summary: user is reviewing payment module.")
                .projectKey("repo:payment")
                .build();
        List<ReviewSessionMessage> messages = IntStream.rangeClosed(1, 25)
                .mapToObj(i -> ReviewSessionMessage.builder()
                        .role(i % 2 == 0 ? "ASSISTANT" : "USER")
                        .content("message-" + i)
                        .createdAt(LocalDateTime.now().plusSeconds(i))
                        .build())
                .toList();
        List<ReviewSessionMemory> memories = List.of(ReviewSessionMemory.builder()
                .id(5L)
                .scope("PROJECT_MEMORY")
                .summary("Project uses Alibaba Java rules.")
                .content("Project uses Alibaba Java rules.")
                .confidence(0.9d)
                .build());

        ConversationContext context = assembler.assemble(
                session,
                "Why is finding high risk?",
                messages,
                memories,
                "Evidence says SQL injection."
        );

        assertThat(context.getPromptContext()).contains("Why is finding high risk?");
        assertThat(context.getPromptContext()).contains("Evidence says SQL injection.");
        assertThat(context.getPromptContext()).contains("message-25");
        assertThat(context.getPromptContext()).doesNotContain("USER: message-1\n");
        assertThat(context.getPromptContext()).contains("Session summary");
        assertThat(context.getPromptContext()).contains("Project uses Alibaba Java rules.");
        assertThat(context.getMemoryIds()).containsExactly(5L);
    }
}
