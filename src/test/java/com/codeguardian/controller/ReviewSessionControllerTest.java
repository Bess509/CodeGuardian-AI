package com.codeguardian.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.codeguardian.dto.ReviewMemoryCreateRequestDTO;
import com.codeguardian.dto.ReviewMemoryDTO;
import com.codeguardian.dto.ReviewSessionChatRequestDTO;
import com.codeguardian.dto.ReviewSessionChatResponseDTO;
import com.codeguardian.dto.ReviewSessionCreateRequestDTO;
import com.codeguardian.dto.ReviewSessionDTO;
import com.codeguardian.dto.ReviewSessionMessageDTO;
import com.codeguardian.service.session.ReviewSessionService;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewSessionControllerTest {

    private final ReviewSessionService sessionService = mock(ReviewSessionService.class);
    private final ReviewSessionController controller = new ReviewSessionController(sessionService);

    @Test
    void createSessionDelegatesWithCurrentUserId() {
        ReviewSessionCreateRequestDTO request = ReviewSessionCreateRequestDTO.builder()
                .projectKey("repo:payment")
                .title("Payment")
                .build();
        ReviewSessionDTO dto = ReviewSessionDTO.builder().id(1L).userId(7L).projectKey("repo:payment").build();
        when(sessionService.createSession(7L, request)).thenReturn(dto);

        try (MockedStatic<StpUtil> mocked = mockStatic(StpUtil.class)) {
            mocked.when(StpUtil::getLoginIdAsLong).thenReturn(7L);

            ReviewSessionDTO response = controller.createSession(request).getBody();

            assertThat(response).isEqualTo(dto);
            verify(sessionService).createSession(7L, request);
        }
    }

    @Test
    void chatDelegatesWithCurrentUserId() {
        ReviewSessionChatRequestDTO request = ReviewSessionChatRequestDTO.builder()
                .content("Explain finding")
                .build();
        ReviewSessionChatResponseDTO dto = ReviewSessionChatResponseDTO.builder()
                .sessionId(11L)
                .memoryIds(List.of(5L))
                .assistantMessage(ReviewSessionMessageDTO.builder().content("answer").build())
                .build();
        when(sessionService.chat(7L, 11L, request)).thenReturn(dto);

        try (MockedStatic<StpUtil> mocked = mockStatic(StpUtil.class)) {
            mocked.when(StpUtil::getLoginIdAsLong).thenReturn(7L);

            ReviewSessionChatResponseDTO response = controller.chat(11L, request).getBody();

            assertThat(response).isEqualTo(dto);
            verify(sessionService).chat(7L, 11L, request);
        }
    }

    @Test
    void addMemoryDelegatesWithCurrentUserId() {
        ReviewMemoryCreateRequestDTO request = ReviewMemoryCreateRequestDTO.builder()
                .content("Remember payment SQL policy")
                .build();
        ReviewMemoryDTO dto = ReviewMemoryDTO.builder().id(3L).userId(7L).sessionId(11L).build();
        when(sessionService.addUserMemory(7L, 11L, request)).thenReturn(dto);

        try (MockedStatic<StpUtil> mocked = mockStatic(StpUtil.class)) {
            mocked.when(StpUtil::getLoginIdAsLong).thenReturn(7L);

            ReviewMemoryDTO response = controller.addMemory(11L, request).getBody();

            assertThat(response).isEqualTo(dto);
            verify(sessionService).addUserMemory(7L, 11L, request);
        }
    }
}
