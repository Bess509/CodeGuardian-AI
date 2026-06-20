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
public class ReviewSessionDTO {
    private Long id;
    private Long userId;
    private String projectKey;
    private String title;
    private String summary;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
