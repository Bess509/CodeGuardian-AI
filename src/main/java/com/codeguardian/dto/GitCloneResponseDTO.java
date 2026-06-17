package com.codeguardian.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Git克隆响应DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GitCloneResponseDTO {
    
    /**
     * 本地存储路径
     */
    private String localPath;
    
    /**
     * 文件列表
     */
    private List<String> fileList;
    
    /**
     * 是否成功
     */
    private boolean success;
    
    /**
     * 错误信息
     */
    private String error;
}
