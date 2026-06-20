package com.codeguardian.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewSessionChatResponseDTO {
    private Long sessionId;
    private ReviewSessionMessageDTO userMessage;
    private ReviewSessionMessageDTO assistantMessage;
    private List<Long> memoryIds;
    private Boolean contextFromCache;
}
