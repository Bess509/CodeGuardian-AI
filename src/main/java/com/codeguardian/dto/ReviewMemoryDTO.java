package com.codeguardian.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewMemoryDTO {
    private Long id;
    private Long userId;
    private String projectKey;
    private Long sessionId;
    private String scope;
    private String memoryType;
    private String content;
    private String summary;
    private String sourceType;
    private String sourceId;
    private Double confidence;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime lastUsedAt;
}
