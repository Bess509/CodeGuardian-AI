package com.codeguardian.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件内容DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileContentDTO {
    
    /**
     * 文件路径（相对路径）
     */
    private String path;
    
    /**
     * 文件内容
     */
    private String content;
}
