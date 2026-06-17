package com.codeguardian.service.ai.impl;

import com.codeguardian.service.ai.config.ModelProviderConfig;
import com.codeguardian.service.ai.dto.AIModelRequest;
import com.codeguardian.service.ai.dto.AIModelResponse;
import com.codeguardian.service.ai.exception.AIModelException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.RequestBody;

import java.util.HashMap;
import java.util.Map;

/**
 * OpenAI兼容的模型提供商基类
 * 
 * <p>提供OpenAI格式API的通用实现，适用于OpenAI和DeepSeek等兼容OpenAI格式的提供商</p>
 * 
 * @author 苏三
 * @since 1.0.0
 */
@Slf4j
public abstract class OpenAICompatibleProvider extends AbstractAIModelProvider {
    
    protected static final String API_ENDPOINT = "/v1/chat/completions";
    
    protected OpenAICompatibleProvider(ModelProviderConfig config, ObjectMapper objectMapper) {
        super(config, objectMapper);
    }
    
    @Override
    protected Request buildRequest(AIModelRequest request) throws AIModelException {
        String model = getModelName(request);
        
        log.debug("{} 构建OpenAI格式请求: model={}, temperature={}, messagesCount={}", 
                getProviderName(), model, request.getTemperature(), 
                request.getMessages() != null ? request.getMessages().size() : 0);
        
        // 构建请求体（OpenAI格式）
        Map<String, Object> requestBody = buildOpenAIRequestBody(request, model);
        
        String url = config.getBaseUrl() + API_ENDPOINT;
        log.debug("{} 请求URL: {}", getProviderName(), url);
        
        RequestBody body = createJsonRequestBody(requestBody);
        
        return createBaseRequestBuilder(url)
                .header("Authorization", "Bearer " + config.getApiKey())
                .post(body)
                .build();
    }
    
    @Override
    protected AIModelResponse parseResponse(String responseBody, AIModelRequest request) 
            throws AIModelException {
        try {
            log.debug("{} 开始解析OpenAI格式响应, 响应体长度: {} 字符", 
                    getProviderName(), responseBody != null ? responseBody.length() : 0);
            
            JsonNode jsonNode = objectMapper.readTree(responseBody);
            AIModelResponse response = parseOpenAIFormatResponse(jsonNode);
            
            log.debug("{} 响应解析成功: model={}, contentLength={}, usageTokens={}", 
                    getProviderName(),
                    response.getModel(),
                    response.getContent() != null ? response.getContent().length() : 0,
                    response.getUsageTokens());
            
            return response;
        } catch (AIModelException e) {
            log.error("{} 解析响应业务异常: {}", getProviderName(), e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("{} 解析响应失败: 响应体前500字符={}", 
                    getProviderName(), 
                    responseBody != null && responseBody.length() > 500 
                            ? responseBody.substring(0, 500) 
                            : responseBody, 
                    e);
            throw new AIModelException(getProviderName(), "解析响应失败", e);
        }
    }
    
    /**
     * 构建OpenAI格式的请求体
     */
    protected Map<String, Object> buildOpenAIRequestBody(AIModelRequest request, String model) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", convertMessages(request.getMessages()));
        requestBody.put("temperature", request.getTemperature());
        
        if (request.getMaxTokens() != null) {
            requestBody.put("max_tokens", request.getMaxTokens());
        }
        
        // 添加扩展参数
        if (request.getExtraParams() != null) {
            requestBody.putAll(request.getExtraParams());
        }
        
        return requestBody;
    }
    
    /**
     * 解析OpenAI格式的响应
     */
    protected AIModelResponse parseOpenAIFormatResponse(JsonNode jsonNode) throws AIModelException {
        // 解析choices
        JsonNode choices = jsonNode.path("choices");
        if (!choices.isArray() || choices.size() == 0) {
            throw new AIModelException(getProviderName(), 
                    "响应中没有choices: " + jsonNode.toString());
        }
        
        JsonNode firstChoice = choices.get(0);
        JsonNode message = firstChoice.path("message");
        String content = message.path("content").asText();
        String finishReason = firstChoice.path("finish_reason").asText();
        
        // 解析usage
        JsonNode usage = jsonNode.path("usage");
        Integer usageTokens = usage.path("total_tokens").asInt(0);
        
        // 解析requestId（不同提供商可能字段名不同）
        String requestId = extractRequestId(jsonNode);
        
        return AIModelResponse.builder()
                .content(content)
                .model(jsonNode.path("model").asText())
                .usageTokens(usageTokens)
                .finishReason(finishReason)
                .metadata(AIModelResponse.ResponseMetadata.builder()
                        .requestId(requestId)
                        .build())
                .build();
    }
    
    /**
     * 提取请求ID（子类可以覆盖以支持不同的字段名）
     */
    protected String extractRequestId(JsonNode jsonNode) {
        // 优先尝试 "id" 字段（OpenAI格式）
        if (jsonNode.has("id")) {
            return jsonNode.path("id").asText();
        }
        // 尝试 "request_id" 字段（某些兼容格式）
        if (jsonNode.has("request_id")) {
            return jsonNode.path("request_id").asText();
        }
        return "";
    }
}

