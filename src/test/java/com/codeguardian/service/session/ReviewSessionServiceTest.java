package com.codeguardian.service.session;

import com.codeguardian.dto.ReviewSessionChatRequestDTO;
import com.codeguardian.dto.ReviewSessionChatResponseDTO;
import com.codeguardian.dto.ReviewSessionCreateRequestDTO;
import com.codeguardian.dto.ReviewSessionDTO;
import com.codeguardian.entity.Finding;
import com.codeguardian.entity.ReviewSession;
import com.codeguardian.entity.ReviewSessionMessage;
import com.codeguardian.enums.SeverityEnum;
import com.codeguardian.repository.FindingRepository;
import com.codeguardian.repository.ReviewSessionMessageRepository;
import com.codeguardian.repository.ReviewSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewSessionServiceTest {

    @Mock
    private ReviewSessionRepository sessionRepository;

    @Mock
    private ReviewSessionMessageRepository messageRepository;

    @Mock
    private FindingRepository findingRepository;

    @Mock
    private ReviewMemoryService memoryService;

    @Mock
    private ConversationContextAssembler contextAssembler;

    @Test
    void createSessionStoresUserProjectAndTitle() {
        ReviewSessionService service = newService();
        when(sessionRepository.save(any(ReviewSession.class))).thenAnswer(invocation -> {
            ReviewSession session = invocation.getArgument(0);
            session.setId(12L);
            return session;
        });

        ReviewSessionDTO created = service.createSession(7L, ReviewSessionCreateRequestDTO.builder()
                .projectKey("repo:payment")
                .title("Payment review assistant")
                .build());

        assertThat(created.getId()).isEqualTo(12L);
        assertThat(created.getProjectKey()).isEqualTo("repo:payment");
        ArgumentCaptor<ReviewSession> captor = ArgumentCaptor.forClass(ReviewSession.class);
        verify(sessionRepository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(7L);
        assertThat(captor.getValue().getTitle()).isEqualTo("Payment review assistant");
    }

    @Test
    void chatPersistsUserAndAssistantMessagesAndUsesAssembledContext() {
        ReviewSessionService service = newService();
        ReviewSession session = ReviewSession.builder()
                .id(11L)
                .userId(7L)
                .projectKey("repo:payment")
                .summary("summary")
                .build();
        when(sessionRepository.findByIdAndUserId(11L, 7L)).thenReturn(Optional.of(session));
        when(messageRepository.findRecentBySessionId(eq(11L), any(Pageable.class))).thenReturn(List.of());
        when(memoryService.recall(7L, "repo:payment", "Explain the SQL risk", 6)).thenReturn(List.of());
        when(findingRepository.findByTaskId(88L)).thenReturn(List.of());
        when(contextAssembler.assemble(eq(session), eq("Explain the SQL risk"), anyList(), anyList(), any()))
                .thenReturn(ConversationContext.builder().promptContext("assembled context").memoryIds(List.of()).build());
        when(messageRepository.save(any(ReviewSessionMessage.class))).thenAnswer(invocation -> {
            ReviewSessionMessage message = invocation.getArgument(0);
            if (message.getId() == null) {
                message.setId("USER".equals(message.getRole()) ? 31L : 32L);
            }
            return message;
        });

        ReviewSessionChatResponseDTO response = service.chat(7L, 11L, ReviewSessionChatRequestDTO.builder()
                .content("Explain the SQL risk")
                .taskId(88L)
                .findingId(99L)
                .build());

        assertThat(response.getAssistantMessage().getContent())
                .contains("回答")
                .doesNotContain("assembled context")
                .doesNotContain("## Task evidence context");
        assertThat(response.getMemoryIds()).isEmpty();
        ArgumentCaptor<ReviewSessionMessage> captor = ArgumentCaptor.forClass(ReviewSessionMessage.class);
        verify(messageRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(ReviewSessionMessage::getRole)
                .containsExactly("USER", "ASSISTANT");
        assertThat(captor.getAllValues().get(0).getTaskId()).isEqualTo(88L);
        assertThat(captor.getAllValues().get(0).getFindingId()).isEqualTo(99L);
    }

    @Test
    void chatAnswersTaskQuestionWithFindingsInsteadOfRawPromptContext() {
        ReviewSessionService service = newService();
        ReviewSession session = ReviewSession.builder()
                .id(21L)
                .userId(7L)
                .projectKey("repo:payment")
                .summary("summary")
                .build();
        String question = "这次审查的问题是什么";
        when(sessionRepository.findByIdAndUserId(21L, 7L)).thenReturn(Optional.of(session));
        when(messageRepository.findRecentBySessionId(eq(21L), any(Pageable.class))).thenReturn(List.of());
        when(memoryService.recall(7L, "repo:payment", question, 6)).thenReturn(List.of());
        when(findingRepository.findByTaskId(42L)).thenReturn(List.of(Finding.builder()
                .id(101L)
                .taskId(42L)
                .severity(SeverityEnum.HIGH.getValue())
                .title("SQL 注入风险")
                .location("UserMapper.java:18")
                .description("用户输入被直接拼接进 SQL 查询。")
                .suggestion("改为参数化查询，并校验输入。")
                .grounded(true)
                .evidenceCount(2)
                .build()));
        when(contextAssembler.assemble(eq(session), eq(question), anyList(), anyList(), any()))
                .thenReturn(ConversationContext.builder()
                        .promptContext("## Task evidence context\nassembled context")
                        .memoryIds(List.of())
                        .build());
        when(messageRepository.save(any(ReviewSessionMessage.class))).thenAnswer(invocation -> {
            ReviewSessionMessage message = invocation.getArgument(0);
            if (message.getId() == null) {
                message.setId("USER".equals(message.getRole()) ? 41L : 42L);
            }
            return message;
        });

        ReviewSessionChatResponseDTO response = service.chat(7L, 21L, ReviewSessionChatRequestDTO.builder()
                .content(question)
                .taskId(42L)
                .build());

        assertThat(response.getAssistantMessage().getContent())
                .contains("回答")
                .contains("这次审查发现 1 个问题")
                .contains("SQL 注入风险")
                .contains("UserMapper.java:18")
                .contains("参数化查询")
                .contains("依据")
                .doesNotContain("## Task evidence context")
                .doesNotContain("assembled context");
    }

    private ReviewSessionService newService() {
        return new ReviewSessionService(
                sessionRepository,
                messageRepository,
                findingRepository,
                memoryService,
                contextAssembler
        );
    }
}
