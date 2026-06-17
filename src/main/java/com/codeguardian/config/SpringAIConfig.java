package com.codeguardian.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import lombok.extern.slf4j.Slf4j;
import com.codeguardian.enums.ModelProviderEnum;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Spring AI配置类
 * 
 * <p>配置多个AI模型提供商，使用Spring AI的统一接口和自动配置特性</p>
 * 
 * @author 苏三
 * @since 1.0.0
 */
@Configuration
@Slf4j
public class SpringAIConfig {

    private final Environment environment;

    public SpringAIConfig(Environment environment) {
        this.environment = environment;
    }
    
    @Value("${spring.ai.qwen.api-key:${app.rag.qwen.api-key:}}")
    private String qwenApiKey;
    
    @Value("${spring.ai.qwen.base-url:${app.rag.qwen.base-url:https://dashscope.aliyuncs.com/compatible-mode}}")
    private String qwenBaseUrl;
    
    @Value("${spring.ai.qwen.chat.options.model:${app.rag.qwen.chat.options.model:qwen3-max}}")
    private String qwenModel;
    
    @Value("${spring.ai.openai.api-key:}")
    private String openAiApiKey;
    
    @Value("${spring.ai.openai.base-url:https://api.openai.com}")
    private String openAiBaseUrl;
    
    @Value("${spring.ai.openai.chat.options.model:gpt-3.5-turbo}")
    private String openAiModel;
    
    @Value("${spring.ai.ollama.base-url:http://localhost:11434}")
    private String ollamaBaseUrl;
    
    @Value("${spring.ai.ollama.chat.options.model:deepseek-r1:8b}")
    private String ollamaModel;
    
    /**
     * 注入Spring AI自动配置的Ollama ChatModel bean
     * 使用required=false确保即使bean不存在也不会报错
     */
    @Autowired(required = false)
    @Qualifier("ollamaChatModel")
    private ChatModel ollamaChatModel;
    
    /**
     * OpenAI ChatModel Bean
     * 只有在配置了非空API Key时才创建，避免启动失败
     */
    @Bean(name = "openAiChatModel")
    @ConditionalOnProperty(
        prefix = "spring.ai.openai",
        name = "api-key",
        matchIfMissing = false  // 不配置则不加载
    )
    public ChatModel openAiChatModel() {
        
        log.info("初始化OpenAI ChatModel: baseUrl={}, model={}", openAiBaseUrl, openAiModel);
        
        OpenAiApi openAiApi = new OpenAiApi(openAiBaseUrl, openAiApiKey);
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .withModel(openAiModel)
                .withTemperature(0.3)
                .build();
        
        return new OpenAiChatModel(openAiApi, options);
    }
    
    /**
     * Qwen ChatModel Bean (使用OpenAI兼容接口)
     * 注意：Spring AI没有原生的Qwen支持，我们使用OpenAI兼容模式
     */
    @Bean(name = "qwenChatModel")
    @ConditionalOnProperty(
        prefix = "spring.ai.qwen",
        name = "api-key",
        matchIfMissing = false  // 不配置则不加载
    )
    public ChatModel qwenChatModel() {
        log.info("初始化Qwen ChatModel: baseUrl={}, model={}", qwenBaseUrl, qwenModel);
        OpenAiApi qwenApi = new OpenAiApi(qwenBaseUrl, qwenApiKey);
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .withModel(qwenModel)
                .withTemperature(0.3)
                .build();
        
        log.info("Qwen ChatModel配置完成: baseUrl={}, model={}, 最终API端点: {}/v1/chat/completions", 
                qwenBaseUrl, qwenModel, qwenBaseUrl);
        return new OpenAiChatModel(qwenApi, options);
    }
    
    /**
     * 默认ChatModel (使用Qwen，如果不可用则使用OpenAI)
     * 使用@Lazy避免循环依赖
     */
    @Bean
    @Primary
    public ChatModel defaultChatModel(
            @Lazy @Qualifier("qwenChatModel") ObjectProvider<ChatModel> qwenChatModelProvider,
            @Lazy @Qualifier("openAiChatModel") ObjectProvider<ChatModel> openAiChatModelProvider) {
        ChatModel qwenChatModel = qwenChatModelProvider.getIfAvailable();
        ChatModel openAiChatModel = openAiChatModelProvider.getIfAvailable();

        if (qwenChatModel != null) {
            log.info("使用Qwen作为默认ChatModel");
            return qwenChatModel;
        }
        if (openAiChatModel != null) {
            log.info("使用OpenAI作为默认ChatModel");
            return openAiChatModel;
        }
        log.warn("没有可用的ChatModel，将返回null");
        return null;
    }
    

    
    /**
     * ChatModel映射，用于根据提供商名称获取对应的ChatModel
     * 使用@Lazy避免循环依赖
     */
    @Bean
    public Map<String, ChatModel> chatModelMap(
            @Lazy @Qualifier("qwenChatModel") ObjectProvider<ChatModel> qwenChatModelProvider,
            @Lazy @Qualifier("openAiChatModel") ObjectProvider<ChatModel> openAiChatModelProvider) {
        Map<String, ChatModel> modelMap = new HashMap<>();
        ChatModel qwenChatModel = qwenChatModelProvider.getIfAvailable();
        ChatModel openAiChatModel = openAiChatModelProvider.getIfAvailable();
        
        // 自定义的OpenAI ChatModel（仅在API key存在时创建）
        if (openAiChatModel != null) {
            modelMap.put(ModelProviderEnum.OPENAI.getCode(), openAiChatModel);
            log.info("注册OpenAI ChatModel (自定义配置)");
        }
        
        // 使用Spring AI自动配置的Ollama ChatModel
        if (ollamaChatModel != null && isDeepSeekConfigured()) {
            modelMap.put(ModelProviderEnum.DEEPSEEK.getCode(), ollamaChatModel);
            log.info("注册Ollama ChatModel (DeepSeek, 来自Spring AI自动配置)");
        }
        
        // 自定义的Qwen ChatModel
        if (qwenChatModel != null) {
            modelMap.put(ModelProviderEnum.QWEN.getCode(), qwenChatModel);
            log.info("注册Qwen ChatModel (自定义配置)");
        }
        
        log.info("已注册的ChatModel: {}", modelMap.keySet());
        if (modelMap.isEmpty()) {
            log.warn("警告：没有可用的ChatModel，应用可能无法正常工作");
        }
        return modelMap;
    }

    private boolean isDeepSeekConfigured() {
        String baseUrl = environment.getProperty("spring.ai.ollama.base-url");
        if (StringUtils.hasText(baseUrl)) {
            return true;
        }
        String appBaseUrl = environment.getProperty("app.rag.ollama.base-url");
        return StringUtils.hasText(appBaseUrl);
    }
}
