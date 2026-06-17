package com.codeguardian.service.ai;

import com.codeguardian.service.ai.dto.AIModelRequest;
import com.codeguardian.service.ai.dto.AIModelResponse;
import com.codeguardian.service.ai.exception.AIModelException;

/**
 * AI模型提供商接口
 * 
 * <p>定义统一的AI模型调用接口，不同的模型提供商实现此接口。
 * 采用策略模式，支持灵活扩展新的AI模型。</p>
 * 
 * @author 苏三
 * @since 1.0.0
 */
public interface AIModelProvider {
    
    /**
     * 获取提供商名称
     * 
     * @return 提供商名称，如：OPENAI, QWEN, DEEPSEEK
     */
    String getProviderName();
    
    /**
     * 调用AI模型进行文本生成
     * 
     * @param request AI模型请求对象
     * @return AI模型响应对象
     * @throws AIModelException 当调用失败时抛出
     */
    AIModelResponse chat(AIModelRequest request) throws AIModelException;
    
    /**
     * 检查提供商是否可用
     * 
     * @return true表示可用，false表示不可用
     */
    boolean isAvailable();
    
    /**
     * 获取提供商支持的模型列表
     * 
     * @return 模型名称列表
     */
    String[] getSupportedModels();
}

