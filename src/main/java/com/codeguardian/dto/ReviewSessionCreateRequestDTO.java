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
public class ReviewSessionCreateRequestDTO {
    @NotBlank(message = "项目标识不能为空")
    private String projectKey;

    private String title;
}
