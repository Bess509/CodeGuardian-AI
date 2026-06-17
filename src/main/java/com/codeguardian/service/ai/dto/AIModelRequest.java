package com.codeguardian.service.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * AI模型请求DTO
 * 
 * <p>封装AI模型调用所需的请求参数</p>
 * 
 * @author 苏三
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIModelRequest {
    
    /**
     * 模型名称
     */
    private String model;
    
    /**
     * 消息列表
     */
    private List<Message> messages;
    
    /**
     * 温度参数（0-2），控制输出的随机性
     */
    @Builder.Default
    private Double temperature = 0.3;
    
    /**
     * 最大token数
     */
    private Integer maxTokens;
    
    /**
     * 其他扩展参数
     */
    private Map<String, Object> extraParams;
    
    /**
     * 消息对象
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        /**
         * 角色：user, assistant, system
         */
        private String role;
        
        /**
         * 消息内容
         */
        private String content;
    }
}

