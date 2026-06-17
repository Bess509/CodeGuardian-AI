package com.codeguardian.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 审查配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewConfig {
    
    /**
     * 是否检查安全问题
     */
    @Builder.Default
    private boolean checkSecurity = true;
    
    /**
     * 是否检查性能问题
     */
    @Builder.Default
    private boolean checkPerformance = true;
    
    /**
     * 是否检查逻辑错误
     */
    @Builder.Default
    private boolean checkLogic = true;
    
    /**
     * 是否检查代码风格
     */
    @Builder.Default
    private boolean checkStyle = false;
    
    /**
     * 是否检查可维护性
     */
    @Builder.Default
    private boolean checkMaintainability = true;
    
    /**
     * 审查策略：SECURITY_FIRST, BALANCED, PERFORMANCE_FIRST
     */
    @Builder.Default
    private ReviewStrategy strategy = ReviewStrategy.BALANCED;
    
    /**
     * 忽略的路径模式列表
     */
    private List<String> ignorePaths;
    
    /**
     * 是否启用AI分析
     */
    @Builder.Default
    private boolean enableAI = true;
    
    /**
     * AI模型配置
     */
    private AIConfig aiConfig;
    
    public enum ReviewStrategy {
        SECURITY_FIRST,    // 安全优先
        BALANCED,          // 均衡模式
        PERFORMANCE_FIRST  // 性能优先
    }
}


