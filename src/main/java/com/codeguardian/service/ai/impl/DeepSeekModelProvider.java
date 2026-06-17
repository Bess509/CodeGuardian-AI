package com.codeguardian.service.ai.impl;

import com.codeguardian.service.ai.config.ModelProviderConfig;
import com.codeguardian.service.ai.dto.AIModelRequest;
import com.codeguardian.service.ai.exception.AIModelException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * DeepSeek模型提供商实现
 * 
 * <p>实现DeepSeek API的调用逻辑（与OpenAI兼容）
 * 支持本地Ollama部署和云端DeepSeek API</p>
 * 
 * @author 苏三
 * @since 1.0.0
 */
@Slf4j
public class DeepSeekModelProvider extends OpenAICompatibleProvider {
    
    private static final String PROVIDER_NAME = "DEEPSEEK";
    
    public DeepSeekModelProvider(ModelProviderConfig config, ObjectMapper objectMapper) {
        super(config, objectMapper);
    }
    
    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }
    
    @Override
    protected Request buildRequest(AIModelRequest request) throws AIModelException {
        String model = getModelName(request);
        
        log.debug("{} 构建DeepSeek请求: model={}, temperature={}, messagesCount={}", 
                getProviderName(), model, request.getTemperature(), 
                request.getMessages() != null ? request.getMessages().size() : 0);
        
        // 构建请求体（OpenAI格式）
        Map<String, Object> requestBody = buildOpenAIRequestBody(request, model);
        
        // 判断是否为本地Ollama（通常baseUrl包含localhost或127.0.0.1）
        boolean isLocalOllama = config.getBaseUrl() != null 
                && (config.getBaseUrl().contains("localhost") 
                    || config.getBaseUrl().contains("127.0.0.1"));
        
        String url;
        if (isLocalOllama) {
            // Ollama使用 /api/chat 端点（OpenAI兼容模式）
            // 如果baseUrl已经包含完整路径，直接使用；否则拼接
            if (config.getBaseUrl().endsWith("/api/chat") || config.getBaseUrl().endsWith("/v1/chat/completions")) {
                url = config.getBaseUrl();
            } else {
                // 默认使用 /v1/chat/completions（Ollama支持OpenAI兼容模式）
                url = config.getBaseUrl() + "/v1/chat/completions";
            }
            log.debug("{} 检测到本地Ollama服务", getProviderName());
        } else {
            // 云端DeepSeek API
            url = config.getBaseUrl() + API_ENDPOINT;
        }
        
        log.debug("{} 请求URL: {}", getProviderName(), url);
        
        RequestBody body = createJsonRequestBody(requestBody);
        
        Request.Builder requestBuilder = createBaseRequestBuilder(url);
        
        // 本地Ollama通常不需要API Key，云端DeepSeek需要
        if (!isLocalOllama && config.getApiKey() != null && !config.getApiKey().trim().isEmpty()) {
            requestBuilder.header("Authorization", "Bearer " + config.getApiKey());
        } else if (isLocalOllama) {
            log.debug("{} 本地Ollama服务，跳过API Key认证", getProviderName());
        }
        
        return requestBuilder.post(body).build();
    }
    
    @Override
    public boolean isAvailable() {
        // 对于本地Ollama服务，apiKey可以为空
        boolean isLocalOllama = config.getBaseUrl() != null 
                && (config.getBaseUrl().contains("localhost") 
                    || config.getBaseUrl().contains("127.0.0.1"));
        
        if (isLocalOllama) {
            // 本地服务只需要baseUrl和enabled状态
            return config != null 
                    && config.getEnabled() 
                    && StringUtils.hasText(config.getBaseUrl());
        } else {
            // 云端服务需要apiKey
            return config != null 
                    && config.getEnabled() 
                    && StringUtils.hasText(config.getBaseUrl())
                    && StringUtils.hasText(config.getApiKey());
        }
    }
    
    @Override
    public String[] getSupportedModels() {
        return new String[]{
            "deepseek-r1:8b",
            "deepseek-chat", 
            "deepseek-coder", 
            "deepseek-chat-32k"
        };
    }
}

