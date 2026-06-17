package com.codeguardian.service.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI模型响应DTO
 * 
 * <p>封装AI模型调用的响应结果</p>
 * 
 * @author 苏三
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIModelResponse {
    
    /**
     * 响应内容
     */
    private String content;
    
    /**
     * 使用的模型名称
     */
    private String model;
    
    /**
     * 使用的token数
     */
    private Integer usageTokens;
    
    /**
     * 完成原因
     */
    private String finishReason;
    
    /**
     * 响应元数据
     */
    private ResponseMetadata metadata;
    
    /**
     * 响应元数据
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResponseMetadata {
        /**
         * 请求ID
         */
        private String requestId;
        
        /**
         * 响应时间（毫秒）
         */
        private Long responseTime;
        
        /**
         * 其他元数据
         */
        private java.util.Map<String, Object> extra;
    }
}

