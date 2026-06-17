package com.codeguardian.service.ai.output;

import com.codeguardian.entity.Finding;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 代码审查响应解析器
 * 
 * <p>解析AI返回的JSON响应并转换为Finding实体列表</p>
 * 
 * @author 苏三
 * @since 1.0.0
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class CodeReviewOutputParser {
    
    private final ObjectMapper objectMapper;
    
    /**
     * 解析AI响应为Finding列表
     * 
     * @param response AI响应内容
     * @return Finding列表
     */
    public List<Finding> parse(String response) {
        try {
            if (response == null || response.trim().isEmpty()) {
                log.warn("AI响应为空，无法解析");
                return new ArrayList<>();
            }
            
            log.debug("开始解析AI响应，原始响应长度: {} 字符", response.length());
            
            // 清理响应文本，提取JSON部分
            String jsonStr = cleanJsonResponse(response);
            
            log.debug("清理后的JSON字符串长度: {} 字符", jsonStr.length());
            
            // 检查是否是有效的JSON格式
            if (!isValidJson(jsonStr)) {
                log.error("========== AI响应不是有效的JSON格式 ==========");
                log.error("响应内容（完整）: {}", response);
                log.error("清理后的内容（完整）: {}", jsonStr);
                log.error("========== 响应内容结束 ==========");
                return new ArrayList<>();
            }
            
            // 解析JSON
            List<FindingDTO> findingDTOs = objectMapper.readValue(
                    jsonStr, 
                    new TypeReference<List<FindingDTO>>() {}
            );
            
            log.info("成功解析AI响应，找到 {} 个问题", findingDTOs.size());
            
            // 转换为Entity
            List<Finding> findings = new ArrayList<>();
            for (FindingDTO dto : findingDTOs) {
                Finding finding = Finding.builder()
                        .severity(com.codeguardian.enums.SeverityEnum.fromName(dto.getSeverity() != null ? dto.getSeverity() : "MEDIUM").getValue())
                        .title(dto.getTitle() != null ? dto.getTitle() : "未命名问题")
                        .location(dto.getLocation() != null ? dto.getLocation() : "未知位置")
                        .startLine(dto.getStartLine())
                        .endLine(dto.getEndLine())
                        .description(dto.getDescription() != null ? dto.getDescription() : "")
                        .suggestion(dto.getSuggestion())
                        .diff(dto.getDiff())
                        .category(dto.getCategory())
                        .source("AI Model")
                        .build();
                findings.add(finding);
            }
            
            return findings;
            
        } catch (com.fasterxml.jackson.core.JsonParseException e) {
            log.error("========== JSON解析失败 ==========");
            log.error("错误信息: {}", e.getMessage());
            log.error("原始响应内容（完整）: {}", response);
            log.error("========== 响应内容结束 ==========");
            return new ArrayList<>();
        } catch (Exception e) {
            log.error("========== 解析AI响应失败 ==========");
            log.error("错误类型: {}", e.getClass().getName());
            log.error("错误信息: {}", e.getMessage());
            log.error("原始响应内容（完整）: {}", response);
            log.error("========== 响应内容结束 ==========", e);
            return new ArrayList<>();
        }
    }
    
    /**
     * 清理JSON响应，移除markdown代码块标记
     */
    private String cleanJsonResponse(String response) {
        if (response == null) {
            return "";
        }
        
        String jsonStr = response.trim();
        
        // 移除开头的markdown代码块标记
        if (jsonStr.startsWith("```json")) {
            jsonStr = jsonStr.substring(7).trim();
        } else if (jsonStr.startsWith("```")) {
            jsonStr = jsonStr.substring(3).trim();
        }
        
        // 移除结尾的markdown代码块标记
        if (jsonStr.endsWith("```")) {
            jsonStr = jsonStr.substring(0, jsonStr.length() - 3).trim();
        }
        
        // 尝试提取JSON数组部分（如果响应包含其他文本）
        // 查找第一个 '[' 和最后一个 ']'
        int firstBracket = jsonStr.indexOf('[');
        int lastBracket = jsonStr.lastIndexOf(']');
        if (firstBracket >= 0 && lastBracket > firstBracket) {
            jsonStr = jsonStr.substring(firstBracket, lastBracket + 1);
        }
        
        return jsonStr.trim();
    }
    
    /**
     * 检查字符串是否是有效的JSON格式
     */
    private boolean isValidJson(String str) {
        if (str == null || str.trim().isEmpty()) {
            return false;
        }
        
        String trimmed = str.trim();
        
        // 检查是否以HTML标签开头（常见错误情况）
        if (trimmed.startsWith("<")) {
            log.warn("响应内容以 '<' 开头，可能是HTML格式而非JSON");
            return false;
        }
        
        // 检查是否以JSON数组或对象开头
        return trimmed.startsWith("[") || trimmed.startsWith("{");
    }
    
    /**
     * Finding DTO（用于JSON解析）
     */
    private static class FindingDTO {
        public String severity;
        public String title;
        public String location;
        public Integer startLine;
        public Integer endLine;
        public String description;
        public String suggestion;
        public String diff;
        public String category;
        
        public String getSeverity() { return severity; }
        public String getTitle() { return title; }
        public String getLocation() { return location; }
        public Integer getStartLine() { return startLine; }
        public Integer getEndLine() { return endLine; }
        public String getDescription() { return description; }
        public String getSuggestion() { return suggestion; }
        public String getDiff() { return diff; }
        public String getCategory() { return category; }
    }
}
