package com.codeguardian.service.rules;

import java.util.List;

/**
 * 规范模板接口
 */
public interface RuleTemplate {
    
    /**
     * 获取模板名称 (如 ALIBABA, GOOGLE)
     */
    String getName();
    
    /**
     * 获取模板描述
     */
    String getDescription();
    
    /**
     * 获取规则列表
     */
    List<RuleDefinition> getRules();
    
    /**
     * 是否支持该语言
     */
    boolean supports(String language);
}