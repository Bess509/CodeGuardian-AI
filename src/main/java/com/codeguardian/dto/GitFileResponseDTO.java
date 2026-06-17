package com.codeguardian.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Git文件内容响应DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GitFileResponseDTO {
    
    /**
     * 文件内容
     */
    private String content;
    
    /**
     * 是否成功
     */
    private boolean success;
    
    /**
     * 错误信息
     */
    private String error;
}
