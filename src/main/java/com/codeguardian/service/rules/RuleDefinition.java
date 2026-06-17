package com.codeguardian.service.rules;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 规则定义模型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuleDefinition {
    /** 规则ID */
    private Integer id;

    /** 规则名称 */
    private String name;
    
    /** 规则描述/要点 */
    private String description;
    
    /** 正则匹配模式 */
    private String pattern;
    
    /** 严重程度: CRITICAL, HIGH, MEDIUM, LOW */
    private String severity;

    /** 问题类别: SECURITY, PERFORMANCE, BUG, CODE_STYLE, MAINTAINABILITY */
    private String category;
    
    /** 权重 (0-100) */
    private int weight;
    
    /** 修复建议 */
    private String suggestion;
}
