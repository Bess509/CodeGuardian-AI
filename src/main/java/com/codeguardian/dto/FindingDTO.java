package com.codeguardian.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 审查发现DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FindingDTO {
    
    private Long id;
    
    /**
     * 严重程度
     */
    private String severity;
    
    /**
     * 问题标题
     */
    private String title;
    
    /**
     * 问题位置
     */
    private String location;
    
    /**
     * 起始行号
     */
    private Integer startLine;
    
    /**
     * 结束行号
     */
    private Integer endLine;
    
    /**
     * 问题描述
     */
    private String description;
    
    /**
     * 修复建议
     */
    private String suggestion;
    
    /**
     * 修复代码差异
     */
    private String diff;
    
    /**
     * 问题类别
     */
    private String category;

    private String source;

    private Boolean grounded;

    private Integer evidenceCount;

    private String evidenceHash;

    private String groundingSummary;
}
