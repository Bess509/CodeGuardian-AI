package com.codeguardian.service.ai.impl;

import com.codeguardian.service.ai.config.ModelProviderConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * OpenAI模型提供商实现
 * 
 * <p>实现OpenAI API的调用逻辑</p>
 * 
 * @author 苏三
 * @since 1.0.0
 */
public class OpenAIModelProvider extends OpenAICompatibleProvider {
    
    private static final String PROVIDER_NAME = "OPENAI";
    
    public OpenAIModelProvider(ModelProviderConfig config, ObjectMapper objectMapper) {
        super(config, objectMapper);
    }
    
    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }
    
    @Override
    public String[] getSupportedModels() {
        return new String[]{"gpt-4", "gpt-4-turbo", "gpt-3.5-turbo", "gpt-3.5-turbo-16k"};
    }
}

