package com.codeguardian.service.ai.impl;

import com.codeguardian.service.ai.AIModelProvider;
import com.codeguardian.service.ai.config.ModelProviderConfig;
import com.codeguardian.service.ai.dto.AIModelRequest;
import com.codeguardian.service.ai.dto.AIModelResponse;
import com.codeguardian.service.ai.exception.AIModelException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * AI模型提供商抽象基类
 * 
 * <p>提供通用的HTTP客户端和基础功能，子类只需实现特定的请求构建和响应解析逻辑</p>
 * 
 * @author 苏三
 * @since 1.0.0
 */
@Slf4j
public abstract class AbstractAIModelProvider implements AIModelProvider {
    
    protected final ModelProviderConfig config;
    protected final ObjectMapper objectMapper;
    private OkHttpClient httpClient;
    
    protected AbstractAIModelProvider(ModelProviderConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }
    
    /**
     * 获取HTTP客户端（单例模式）
     */
    protected OkHttpClient getHttpClient() {
        if (httpClient == null) {
            httpClient = new OkHttpClient.Builder()
                    .connectTimeout(config.getConnectTimeout(), TimeUnit.SECONDS)
                    .readTimeout(config.getReadTimeout(), TimeUnit.SECONDS)
                    .writeTimeout(config.getWriteTimeout(), TimeUnit.SECONDS)
                    .build();
        }
        return httpClient;
    }
    
    @Override
    public AIModelResponse chat(AIModelRequest request) throws AIModelException {
        if (!isAvailable()) {
            log.warn("{} 模型提供商不可用: baseUrl={}, apiKey配置={}", 
                    getProviderName(), 
                    config.getBaseUrl() != null ? "已配置" : "未配置",
                    config.getApiKey() != null && !config.getApiKey().isEmpty() ? "已配置" : "未配置");
            throw new AIModelException(getProviderName(), "模型提供商不可用");
        }
        
        String model = getModelName(request);
        log.info("{} 开始调用API: model={}, baseUrl={}", 
                getProviderName(), model, config.getBaseUrl());
        
        try {
            // 构建请求
            long buildStartTime = System.currentTimeMillis();
            Request httpRequest = buildRequest(request);
            long buildTime = System.currentTimeMillis() - buildStartTime;
            
            log.debug("{} 请求构建完成: 耗时={}ms, URL={}", 
                    getProviderName(), buildTime, httpRequest.url());
            
            // 记录请求信息
            log.debug("{} 请求URL: {}", getProviderName(), httpRequest.url());
            if (httpRequest.body() != null) {
                log.debug("{} 请求体已构建", getProviderName());
            }
            
            // 执行请求
            long startTime = System.currentTimeMillis();
            log.info("{} 发送HTTP请求...", getProviderName());
            
            try (Response response = getHttpClient().newCall(httpRequest).execute()) {
                long responseTime = System.currentTimeMillis() - startTime;
                
                String responseBody = response.body().string();
                int responseBodyLength = responseBody != null ? responseBody.length() : 0;
                
                log.info("{} HTTP响应接收: status={}, 响应时间={}ms, 响应体大小={} bytes", 
                        getProviderName(), response.code(), responseTime, responseBodyLength);
                
                // 打印完整的响应体（用于调试）
                if (responseBody != null && !responseBody.isEmpty()) {
                    log.debug("{} HTTP响应体（完整）: {}", getProviderName(), responseBody);
                    // 如果响应体很长，也打印前2000字符
                    if (responseBodyLength > 2000) {
                        log.debug("{} HTTP响应体（前2000字符）: {}", 
                                getProviderName(), responseBody.substring(0, 2000));
                    }
                }
                
                if (!response.isSuccessful()) {
                    log.error("{} API调用失败: status={}, 响应体前1000字符={}", 
                            getProviderName(), 
                            response.code(), 
                            responseBodyLength > 1000 ? responseBody.substring(0, 1000) : responseBody);
                    log.error("{} API调用失败: 完整响应体={}", getProviderName(), responseBody);
                    throw new AIModelException(
                            getProviderName(), 
                            "API调用失败", 
                            response.code()
                    );
                }
                
                // 解析响应
                long parseStartTime = System.currentTimeMillis();
                AIModelResponse aiResponse = parseResponse(responseBody, request);
                long parseTime = System.currentTimeMillis() - parseStartTime;
                
                // 设置元数据
                if (aiResponse.getMetadata() == null) {
                    aiResponse.setMetadata(AIModelResponse.ResponseMetadata.builder()
                            .responseTime(responseTime)
                            .build());
                } else {
                    aiResponse.getMetadata().setResponseTime(responseTime);
                }
                
                log.info("{} API调用成功: 总耗时={}ms (网络={}ms, 解析={}ms), model={}, usageTokens={}, contentLength={}", 
                        getProviderName(), 
                        responseTime,
                        responseTime - parseTime,
                        parseTime,
                        aiResponse.getModel(),
                        aiResponse.getUsageTokens() != null ? aiResponse.getUsageTokens() : 0,
                        aiResponse.getContent() != null ? aiResponse.getContent().length() : 0);
                
                if (aiResponse.getMetadata() != null && aiResponse.getMetadata().getRequestId() != null) {
                    log.debug("{} 请求ID: {}", getProviderName(), aiResponse.getMetadata().getRequestId());
                }
                
                return aiResponse;
            }
        } catch (AIModelException e) {
            log.error("{} API调用业务异常: {}", getProviderName(), e.getMessage(), e);
            throw e;
        } catch (IOException e) {
            log.error("{} API调用IO异常: {}", getProviderName(), e.getMessage(), e);
            throw new AIModelException(getProviderName(), "API调用异常: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("{} API调用未知异常", getProviderName(), e);
            throw new AIModelException(getProviderName(), "API调用异常: " + e.getMessage(), e);
        }
    }
    
    @Override
    public boolean isAvailable() {
        return config != null 
                && config.getEnabled() 
                && StringUtils.hasText(config.getBaseUrl())
                && StringUtils.hasText(config.getApiKey());
    }
    
    /**
     * 构建HTTP请求
     * 
     * @param request AI模型请求
     * @return HTTP请求对象
     * @throws AIModelException 构建失败时抛出
     */
    protected abstract Request buildRequest(AIModelRequest request) throws AIModelException;
    
    /**
     * 解析API响应
     * 
     * @param responseBody 响应体
     * @param request 原始请求
     * @return AI模型响应对象
     * @throws AIModelException 解析失败时抛出
     */
    protected abstract AIModelResponse parseResponse(String responseBody, AIModelRequest request) 
            throws AIModelException;
    
    /**
     * 创建JSON请求体
     */
    protected RequestBody createJsonRequestBody(Object body) throws AIModelException {
        try {
            String json = objectMapper.writeValueAsString(body);
            return RequestBody.create(json, MediaType.get("application/json; charset=utf-8"));
        } catch (Exception e) {
            throw new AIModelException(getProviderName(), "构建请求体失败", e);
        }
    }
    
    /**
     * 转换消息列表为Map格式（通用方法）
     * 
     * @param messages 消息列表
     * @return Map格式的消息列表
     */
    protected List<Map<String, String>> convertMessages(List<AIModelRequest.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return messages.stream()
                .map(msg -> {
                    Map<String, String> map = new HashMap<>();
                    map.put("role", msg.getRole());
                    map.put("content", msg.getContent());
                    return map;
                })
                .collect(Collectors.toList());
    }
    
    /**
     * 获取模型名称（如果请求中未指定，使用配置的默认模型）
     */
    protected String getModelName(AIModelRequest request) {
        return request.getModel() != null && !request.getModel().trim().isEmpty()
                ? request.getModel()
                : config.getDefaultModel();
    }
    
    /**
     * 构建基础请求头
     */
    protected Request.Builder createBaseRequestBuilder(String url) {
        return new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json");
    }
}

