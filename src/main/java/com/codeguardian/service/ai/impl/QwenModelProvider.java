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
 * 阿里云通义千问模型提供商实现
 * 
 * <p>实现阿里云DashScope API的调用逻辑</p>
 * 
 * @author 苏三
 * @since 1.0.0
 */
@Slf4j
public class QwenModelProvider extends AbstractAIModelProvider {
    
    private static final String PROVIDER_NAME = "QWEN";
    private static final String API_ENDPOINT = "/api/v1/services/aigc/text-generation/generation";
    
    public QwenModelProvider(ModelProviderConfig config, ObjectMapper objectMapper) {
        super(config, objectMapper);
    }
    
    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }
    
    @Override
    protected Request buildRequest(AIModelRequest request) throws AIModelException {
        String model = getModelName(request);
        
        log.debug("{} 构建阿里千问格式请求: model={}, temperature={}, messagesCount={}", 
                getProviderName(), model, request.getTemperature(), 
                request.getMessages() != null ? request.getMessages().size() : 0);
        
        // 判断是否使用兼容模式（baseUrl包含compatible-mode）
        boolean useCompatibleMode = config.getBaseUrl() != null 
                && config.getBaseUrl().contains("compatible-mode");
        
        String url;
        Map<String, Object> requestBody;
        
        if (useCompatibleMode) {
            // 兼容模式：使用OpenAI格式
            log.debug("{} 使用兼容模式（OpenAI格式）", getProviderName());
            // baseUrl可能已经包含完整路径，也可能只包含基础路径
            if (config.getBaseUrl().endsWith("/chat/completions")) {
                url = config.getBaseUrl();
            } else {
                // 如果baseUrl是 https://dashscope.aliyuncs.com/compatible-mode/v1
                // 需要拼接 /chat/completions
                url = config.getBaseUrl() + "/chat/completions";
            }
            requestBody = buildOpenAICompatibleRequestBody(request, model);
        } else {
            // 原生模式：使用DashScope格式
            log.debug("{} 使用原生模式（DashScope格式）", getProviderName());
            url = config.getBaseUrl() + API_ENDPOINT;
            requestBody = buildQwenRequestBody(request, model);
        }
        
        log.debug("{} 请求URL: {}", getProviderName(), url);
        
        RequestBody body = createJsonRequestBody(requestBody);
        
        Request.Builder requestBuilder = createBaseRequestBuilder(url);
        
        // 兼容模式使用Bearer认证，原生模式使用X-DashScope-API-Key
        if (useCompatibleMode) {
            requestBuilder.header("Authorization", "Bearer " + config.getApiKey());
        } else {
            requestBuilder.header("X-DashScope-API-Key", config.getApiKey());
        }
        
        return requestBuilder.post(body).build();
    }
    
    @Override
    protected AIModelResponse parseResponse(String responseBody, AIModelRequest request) 
            throws AIModelException {
        try {
            log.debug("{} 开始解析响应, 响应体长度: {} 字符", 
                    getProviderName(), responseBody != null ? responseBody.length() : 0);
            
            JsonNode jsonNode = objectMapper.readTree(responseBody);
            
            // 判断是否使用兼容模式（检查响应格式）
            boolean useCompatibleMode = jsonNode.has("choices") && !jsonNode.has("output");
            
            if (useCompatibleMode) {
                // 兼容模式：使用OpenAI格式解析
                log.debug("{} 使用兼容模式解析（OpenAI格式）", getProviderName());
                return parseOpenAICompatibleResponse(jsonNode);
            } else {
                // 原生模式：使用DashScope格式解析
                log.debug("{} 使用原生模式解析（DashScope格式）", getProviderName());
                return parseQwenNativeResponse(jsonNode);
            }
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
    
    @Override
    public String[] getSupportedModels() {
        return new String[]{"qwen3-max", "qwen-turbo", "qwen-plus", "qwen-max", "qwen-max-longcontext"};
    }
    
    /**
     * 构建OpenAI兼容格式的请求体（用于兼容模式）
     */
    private Map<String, Object> buildOpenAICompatibleRequestBody(AIModelRequest request, String model) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", convertMessages(request.getMessages()));
        requestBody.put("temperature", request.getTemperature());
        
        if (request.getMaxTokens() != null) {
            requestBody.put("max_tokens", request.getMaxTokens());
        }
        
        return requestBody;
    }
    
    /**
     * 构建DashScope原生格式的请求体
     */
    private Map<String, Object> buildQwenRequestBody(AIModelRequest request, String model) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("input", Map.of("messages", convertMessages(request.getMessages())));
        requestBody.put("parameters", Map.of("temperature", request.getTemperature()));
        return requestBody;
    }
    
    /**
     * 解析OpenAI兼容格式的响应
     */
    private AIModelResponse parseOpenAICompatibleResponse(JsonNode jsonNode) throws AIModelException {
        JsonNode choices = jsonNode.path("choices");
        if (!choices.isArray() || choices.size() == 0) {
            throw new AIModelException(getProviderName(), "响应中没有choices");
        }
        
        JsonNode firstChoice = choices.get(0);
        JsonNode message = firstChoice.path("message");
        String content = message.path("content").asText();
        String finishReason = firstChoice.path("finish_reason").asText();
        
        JsonNode usage = jsonNode.path("usage");
        Integer usageTokens = usage.path("total_tokens").asInt(0);
        
        String requestId = jsonNode.path("id").asText("");
        
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
     * 解析DashScope原生格式的响应
     */
    private AIModelResponse parseQwenNativeResponse(JsonNode jsonNode) throws AIModelException {
        // 阿里千问响应格式：output.choices[0].message.content
        JsonNode output = jsonNode.path("output");
        if (output.isMissingNode()) {
            log.error("{} 响应格式错误: 缺少output字段, 响应体={}", 
                    getProviderName(), jsonNode.toString());
            throw new AIModelException(getProviderName(), 
                    "响应中没有output字段: " + jsonNode.toString());
        }
        
        JsonNode choices = output.path("choices");
        if (!choices.isArray() || choices.size() == 0) {
            log.error("{} 响应格式错误: choices为空或不是数组, 响应体={}", 
                    getProviderName(), jsonNode.toString());
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
        
        String requestId = jsonNode.path("request_id").asText("");
        log.debug("{} 原生模式响应解析成功: model={}, contentLength={}, usageTokens={}, requestId={}", 
                getProviderName(),
                jsonNode.path("model").asText(),
                content != null ? content.length() : 0,
                usageTokens,
                requestId);
        
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
}

