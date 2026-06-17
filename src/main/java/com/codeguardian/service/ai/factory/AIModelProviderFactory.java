package com.codeguardian.service.ai.factory;

import com.codeguardian.service.ai.AIModelProvider;
import com.codeguardian.service.ai.config.ModelProviderConfig;
import com.codeguardian.service.ai.exception.AIModelException;
import com.codeguardian.service.ai.impl.DeepSeekModelProvider;
import com.codeguardian.service.ai.impl.OpenAIModelProvider;
import com.codeguardian.service.ai.impl.QwenModelProvider;
import com.codeguardian.enums.ModelProviderEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI模型提供商工厂
 * 
 * <p>负责创建和管理AI模型提供商实例，采用单例模式确保每个提供商只有一个实例</p>
 * 
 * @author 苏三
 * @since 1.0.0
 */
@Component
@Slf4j
public class AIModelProviderFactory {
    
    private final ObjectMapper objectMapper;
    private final Map<String, AIModelProvider> providerCache = new ConcurrentHashMap<>();
    
    public AIModelProviderFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    
    /**
     * 创建或获取模型提供商实例
     * 
     * @param config 模型提供商配置
     * @return 模型提供商实例
     * @throws AIModelException 当提供商类型不支持时抛出
     */
    public AIModelProvider createProvider(ModelProviderConfig config) throws AIModelException {
        if (config == null || !config.getEnabled()) {
            throw new AIModelException("UNKNOWN", "配置为空或未启用");
        }

        ModelProviderEnum provider = ModelProviderEnum.from(config.getProviderName())
                .orElseThrow(() -> new IllegalArgumentException("不支持的AI模型提供商: " + config.getProviderName()));
        String cacheKey = provider.getCode() + ":" + config.getBaseUrl();
        
        // 从缓存获取
        return providerCache.computeIfAbsent(cacheKey, key -> {
            log.info("创建AI模型提供商: {}", provider.getCode());
            return doCreateProvider(provider, config);
        });
    }
    
    /**
     * 根据提供商名称创建提供商实例
     */
    private AIModelProvider doCreateProvider(ModelProviderEnum provider, ModelProviderConfig config) {
        switch (provider) {
            case OPENAI:
                return new OpenAIModelProvider(config, objectMapper);
            case QWEN:
                return new QwenModelProvider(config, objectMapper);
            case DEEPSEEK:
                return new DeepSeekModelProvider(config, objectMapper);
            default:
                throw new IllegalArgumentException("不支持的AI模型提供商: " + provider.getCode());
        }
    }
    
    /**
     * 清除缓存
     */
    public void clearCache() {
        providerCache.clear();
        log.info("已清除AI模型提供商缓存");
    }
    
    /**
     * 获取缓存大小
     */
    public int getCacheSize() {
        return providerCache.size();
    }
}
