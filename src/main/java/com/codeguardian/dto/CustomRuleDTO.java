package com.codeguardian.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 自定义规范规则DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomRuleDTO {
    /** 名称 */
    private String name;
    /** 要点/描述 */
    private String point;
    /** 正则模式（匹配违规） */
    private String pattern;
    /** 语言：JAVA/JS/TS/PY */
    private String language;
    /** 严重等级：CRITICAL/HIGH/MEDIUM/LOW */
    private String severity;
    /** 权重（0-100）用于评分与排序 */
    private Integer weight;
}
