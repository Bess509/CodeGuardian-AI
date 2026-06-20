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
public class ReviewSessionChatRequestDTO {
    @NotBlank(message = "消息内容不能为空")
    private String content;

    private Long taskId;

    private Long findingId;
}
