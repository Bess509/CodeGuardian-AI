package com.codeguardian.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * AI配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIConfig {
    
    /**
     * AI服务提供商：OPENAI, CLAUDE, LOCAL
     */
    @Builder.Default
    private String provider = "OPENAI";
    
    /**
     * API密钥
     */
    private String apiKey;
    
    /**
     * API端点URL
     */
    private String apiUrl;
    
    /**
     * 模型名称
     */
    @Builder.Default
    private String model = "gpt-4";
    
    /**
     * 温度参数（0-1）
     */
    @Builder.Default
    private double temperature = 0.3;
    
    /**
     * 最大token数
     */
    @Builder.Default
    private int maxTokens = 2000;
}


