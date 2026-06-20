package com.codeguardian.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewMemoryCreateRequestDTO {
    @NotBlank(message = "记忆内容不能为空")
    private String content;

    private String memoryType;

    private String sourceId;
}
