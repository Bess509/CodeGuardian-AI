package com.codeguardian.service.ai.util;

import lombok.experimental.UtilityClass;

/**
 * Prompt构建工具类
 * 
 * <p>提供统一的Prompt构建方法</p>
 * 
 * @author 苏三
 * @since 1.0.0
 */
@UtilityClass
public class PromptBuilder {
    
    /**
     * 构建代码审查Prompt
     * 
     * @param codeContent 代码内容
     * @param language 代码语言
     * @return 构建好的Prompt
     */
    public String buildCodeReviewPrompt(String codeContent, String language) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个资深的代码审查专家。请审查以下");
        if (language != null && !language.isEmpty()) {
            prompt.append(language);
        }
        prompt.append("代码，识别出潜在的bug、安全漏洞、性能问题和代码风格问题。\n\n");
        prompt.append("请以JSON数组格式返回结果，并且\n");
        prompt.append("- 所有字段内容必须使用中文回答（不要出现英文）。\n");
        prompt.append("- location 字段请使用 '文件名:行号' 格式，例如 'UserService.java:7'。\n");
        prompt.append("- 如果无法确定文件名，仍提供行号，并尽量推断文件名。\n\n");
        prompt.append("每个问题包含以下字段：\n");
        prompt.append("- severity: 严重程度（CRITICAL, HIGH, MEDIUM, LOW）\n");
        prompt.append("- title: 问题标题\n");
        prompt.append("- location: 问题位置描述\n");
        prompt.append("- startLine: 起始行号（可选）\n");
        prompt.append("- endLine: 结束行号（可选）\n");
        prompt.append("- description: 问题描述\n");
        prompt.append("- suggestion: 修复建议\n");
        prompt.append("- diff: 修复代码差异（使用 unified diff 或最常见的 +/− 行前缀形式）\n");
        prompt.append("- category: 问题类别（SECURITY, PERFORMANCE, BUG, CODE_STYLE, MAINTAINABILITY）\n\n");
        prompt.append("代码内容：\n");
        prompt.append("```\n");
        prompt.append(codeContent);
        prompt.append("\n```\n\n");
        prompt.append("请直接返回JSON数组，不要包含其他文字说明。");
        
        return prompt.toString();
    }
}
