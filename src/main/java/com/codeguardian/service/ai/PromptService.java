package com.codeguardian.service.ai;

import com.codeguardian.entity.Finding;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 提示词服务
 * 
 * <p>使用Spring AI的PromptTemplate构建提示词</p>
 * 
 * @author 苏三
 * @since 1.0.0
 */
@Service
@Slf4j
public class PromptService {
    
    /**
     * 代码审查提示词模板
     */
    private static final String CODE_REVIEW_PROMPT_TEMPLATE = """
        你是一个资深的代码审查专家。请审查以下{language}代码，识别出潜在的bug、安全漏洞、性能问题和代码风格问题。
        
        {tool_guidance}
        
        {context_section}
        
        **重要要求：**
        1. 所有字段内容必须使用中文回答（不要出现英文）。
        2. 代码已经包含行号（每行前面有行号，格式为"行号: 代码内容"），请严格按照代码中显示的行号来填写startLine和endLine字段。
        3. location字段必须使用"文件名:行号"格式，例如"UserService.java:7"。如果代码中有类名，请从类名推断文件名（Java类名对应.java文件）。
        4. description字段要简洁明了，直接说明问题，不要使用"The method..."这样的英文句式开头。
        5. suggestion字段要简洁明了，直接说明修复建议，格式为"建议：具体建议内容"。
        6. diff字段必须使用标准的diff格式，每行以"- "（删除）或"+ "（添加）开头，不要包含其他前缀或说明文字。
        7. startLine和endLine必须与代码中显示的行号完全一致，不要使用代码内容中的行号，而是使用代码前面显示的行号。
        
        请以JSON数组格式返回结果，每个问题包含以下字段：
        - severity: 严重程度（CRITICAL, HIGH, MEDIUM, LOW）
        - title: 问题标题（使用中文）
        - location: 问题位置，格式为"文件名:行号"，例如"UserService.java:7"
        - startLine: 起始行号（整数，必须提供，必须与代码中显示的行号一致）
        - endLine: 结束行号（整数，如果只有一行则与startLine相同，必须与代码中显示的行号一致）
        - description: 问题描述（使用中文，简洁明了，直接说明问题）
        - suggestion: 修复建议（使用中文，格式为"建议：具体建议内容"）
        - diff: 修复代码差异（使用标准diff格式，每行以"- "或"+ "开头，包含完整的修复代码）
        - category: 问题类别（SECURITY, PERFORMANCE, BUG, CODE_STYLE, MAINTAINABILITY）
        
        **示例格式：**
        {{
          "severity": "CRITICAL",
          "title": "SQL注入风险",
          "location": "UserService.java:7",
          "startLine": 7,
          "endLine": 7,
          "description": "用户输入直接拼接到SQL语句中，可能导致SQL注入攻击。",
          "suggestion": "建议：使用PreparedStatement进行参数绑定，避免字符串拼接。",
          "diff": "- String sql = \\"SELECT * FROM users WHERE name = \\" + username;\\n+ String sql = \\"SELECT * FROM users WHERE name = ?\\";\\n+ PreparedStatement ps = conn.prepareStatement(sql);\\n+ ps.setString(1, username);\\n+ ResultSet rs = ps.executeQuery();",
          "category": "SECURITY"
        }}
        
        代码内容（已包含行号）：
        ```
        {codeContent}
        ```
        
        请直接返回JSON数组，不要包含其他文字说明。
        """;
    
    private final PromptTemplate codeReviewPromptTemplate;
    
    public PromptService() {
        this.codeReviewPromptTemplate = new PromptTemplate(CODE_REVIEW_PROMPT_TEMPLATE);
    }
    
    /**
     * 构建代码审查提示词
     * 
     * @param codeContent 代码内容
     * @param language 代码语言
     * @return Prompt对象
     */
    public Prompt buildCodeReviewPrompt(String codeContent, String language) {
        return buildCodeReviewPrompt(codeContent, language, null, null);
    }

    /**
     * 构建代码审查提示词（带上下文）
     * 
     * @param codeContent 代码内容
     * @param language 代码语言
     * @param context 检索到的相关上下文（RAG）
     * @return Prompt对象
     */
    public Prompt buildCodeReviewPrompt(String codeContent, String language, String context) {
        return buildCodeReviewPrompt(codeContent, language, context, null);
    }

    /**
     * 构建代码审查提示词（带上下文和已有发现）
     * 
     * @param codeContent 代码内容
     * @param language 代码语言
     * @param context 检索到的相关上下文（RAG）
     * @param existingFindings 已有的静态分析发现
     * @return Prompt对象
     */
    public Prompt buildCodeReviewPrompt(String codeContent, String language, String context, List<Finding> existingFindings) {
        if (language == null || language.trim().isEmpty()) {
            language = "代码";
        }
        
        // 为代码添加行号，确保AI能准确识别行号
        String codeWithLineNumbers = addLineNumbers(codeContent);
        
        String contextSection = "";
        if (context != null && !context.trim().isEmpty()) {
            contextSection = "**参考RAG知识库（类似问题与修复示例）：**\n" + context + "\n";
        }

        String toolGuidance;
        if (existingFindings != null && !existingFindings.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("**已完成的静态分析结果：**\n");
            sb.append("系统已经运行了静态分析工具（如Semgrep），发现了以下问题。请仔细复核这些问题，如果确认属实请包含在最终报告中，并结合你的专家知识发现更多深层问题（如逻辑漏洞）：\n");
            for (Finding f : existingFindings) {
                sb.append(String.format("- [行号: %d] [%s] %s\n", f.getStartLine(), f.getSeverity(), f.getDescription()));
            }
            sb.append("\n注意：不需要重复调用静态分析工具，请专注于逻辑审查和确认上述问题。\n");
            toolGuidance = sb.toString();
        } else {
            toolGuidance = """
            **工具使用能力：**
            你拥有 `javaSyntaxAnalysis`（语法检查）和 `semgrepAnalysis`（静态安全扫描）两个强大的分析工具。
            
            **调用策略（由你全权决定）：**
            1. **按需调用**：只有当你认为代码存在潜在问题且需要工具确认时，才调用工具。
            2. **循序渐进**：建议先自行分析代码逻辑。如果发现复杂的语法结构或不确定的安全风险，再请求工具支持。
            3. **不要盲目调用**：如果是简单的逻辑或伪代码，无需调用工具。
            """;
        }
        
        Map<String, Object> variables = Map.of(
                "codeContent", codeWithLineNumbers,
                "language", language,
                "context_section", contextSection,
                "tool_guidance", toolGuidance
        );
        
        Prompt prompt = codeReviewPromptTemplate.create(variables);
        
        log.debug("构建代码审查提示词: language={}, hasContext={}, hasFindings={}, codeLength={}", 
                language, 
                (context != null && !context.isEmpty()), 
                (existingFindings != null && !existingFindings.isEmpty()),
                codeContent.length());
        
        return prompt;
    }
    
    /**
     * 为代码添加行号
     */
    private String addLineNumbers(String code) {
        if (code == null || code.isEmpty()) {
            return "";
        }
        
        StringBuilder sb = new StringBuilder();
        String[] lines = code.split("\\r?\\n", -1); // 保留空行
        
        for (int i = 0; i < lines.length; i++) {
            // 格式: "1: public class Test {"
            sb.append(i + 1).append(": ").append(lines[i]).append("\n");
        }
        
        return sb.toString();
    }
}

