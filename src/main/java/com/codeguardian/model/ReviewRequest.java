package com.codeguardian.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 代码审查请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewRequest {
    
    /**
     * 审查类型：PROJECT, DIRECTORY, FILE, SNIPPET
     */
    private ReviewType type;
    
    /**
     * 项目路径（当type为PROJECT或DIRECTORY时使用）
     */
    private String projectPath;
    
    /**
     * 文件路径（当type为FILE时使用）
     */
    private String filePath;
    
    /**
     * 代码片段（当type为SNIPPET时使用）
     */
    private String codeSnippet;
    
    /**
     * 代码语言（当type为SNIPPET时使用）
     */
    private String language;
    
    /**
     * 审查配置
     */
    private ReviewConfig config;
    
    public enum ReviewType {
        PROJECT,    // 整个项目
        DIRECTORY,  // 目录
        FILE,       // 单个文件
        SNIPPET     // 代码片段
    }
}


