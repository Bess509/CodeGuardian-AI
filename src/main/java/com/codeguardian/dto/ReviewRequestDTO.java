package com.codeguardian.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 代码审查请求DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewRequestDTO {
    
    /**
     * 审查类型：PROJECT, DIRECTORY, FILE, SNIPPET, GIT
     */
    @NotBlank(message = "审查类型不能为空")
    private String reviewType;
    
    /**
     * 项目路径（当type为PROJECT或DIRECTORY时使用）
     */
    private String projectPath;
    
    /**
     * 目录路径（当type为DIRECTORY时使用）
     */
    private String directoryPath;
    
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
     * Git仓库地址（当type为GIT时使用）
     */
    private String gitUrl;
    
    /**
     * Git用户名（可选）
     */
    private String gitUsername;
    
    /**
     * Git密码/令牌（可选，仅会话态）
     */
    private String gitPassword;
    
    /**
     * 任务名称（可选，默认自动生成）
     */
    private String taskName;

    private Long sessionId;

    private String projectKey;
    
    /**
     * AI模型提供商（可选，默认使用配置的provider）
     * 可选值：OPENAI, QWEN, DEEPSEEK
     */
    private String modelProvider;

    /**
     * 是否仅使用规范规则进行审查（不调用大模型）
     */
    @Builder.Default
    private Boolean rulesOnly = false;

    /**
     * 规范模板：ALIBABA, GOOGLE, AIRBNB, PEP8, CUSTOM
     */
    private String ruleTemplate;

    /**
     * 自定义规范规则（仅当ruleTemplate=CUSTOM时可用）
     */
    private java.util.List<CustomRuleDTO> customRules;
    
    /**
     * 上传的文件列表（当type为DIRECTORY或PROJECT且直接上传文件内容时使用）
     */
    private java.util.List<FileContentDTO> files;

    /**
     * 是否启用RAG知识库增强
     */
    @Builder.Default
    private Boolean enableRag = true;

    /**
     * Whether to run the controlled dual-role review agent workflow.
     */
    @Builder.Default
    private Boolean agentMode = false;

    /**
     * Optional reviewer roles for agent council mode. When empty, the legacy single reviewer is used.
     * Supported values include GENERAL, SECURITY, CORRECTNESS, TESTING, MAINTAINABILITY and COMPANY_POLICY.
     */
    private java.util.List<String> reviewerRoles;

    private String includePaths;

    private String excludePaths;

    private Boolean diffOnly;

    private String baseCommit;

    private String headCommit;

    private java.util.List<String> changedFiles;
}
