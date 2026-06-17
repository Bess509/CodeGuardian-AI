package com.codeguardian.service.ai.config;

import com.codeguardian.config.AIConfigProperties;
import com.codeguardian.enums.ModelProviderEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * AI模型配置管理器
 * 
 * <p>负责管理多个AI模型提供商的配置，支持从配置文件或环境变量加载</p>
 * 
 * @author 苏三
 * @since 1.0.0
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class AIModelConfigManager {
    
    private final AIConfigProperties aiConfigProperties;
    private final Map<String, ModelProviderConfig> configCache = new HashMap<>();
    
    /**
     * 获取默认模型提供商配置
     */
    public ModelProviderConfig getDefaultConfig() {
        return getConfig(aiConfigProperties.getProvider());
    }
    
    /**
     * 根据提供商名称获取配置
     */
    public ModelProviderConfig getConfig(String providerName) {
        String normalizedProviderName = providerName;
        if (normalizedProviderName == null || normalizedProviderName.trim().isEmpty()) {
            normalizedProviderName = aiConfigProperties.getProvider();
        }

        ModelProviderEnum provider = ModelProviderEnum.from(normalizedProviderName)
                .orElse(ModelProviderEnum.OPENAI);
        return configCache.computeIfAbsent(provider.getCode(), k -> buildConfig(provider));
    }
    
    /**
     * 构建模型提供商配置
     */
    private ModelProviderConfig buildConfig(ModelProviderEnum provider) {
        String upperProviderName = provider.getCode();
        
        // 从配置中获取该提供商的配置
        AIConfigProperties.ProviderConfig providerConfig = 
                aiConfigProperties.getProviderConfig(upperProviderName);
        
        ModelProviderConfig.ModelProviderConfigBuilder builder = ModelProviderConfig.builder()
                .providerName(upperProviderName);
        
        boolean globallyEnabled = aiConfigProperties.getEnabled() == null || aiConfigProperties.getEnabled();

        if (providerConfig != null) {
            // 使用该提供商的独立配置
            builder.baseUrl(providerConfig.getBaseUrl())
                    .apiKey(providerConfig.getApiKey())
                    .defaultModel(providerConfig.getModel())
                    .enabled(globallyEnabled && (providerConfig.getEnabled() != null ? providerConfig.getEnabled() : true))
                    .connectTimeout(providerConfig.getConnectTimeout() != null 
                            ? providerConfig.getConnectTimeout() 
                            : aiConfigProperties.getTimeout())
                    .readTimeout(providerConfig.getReadTimeout() != null 
                            ? providerConfig.getReadTimeout() 
                            : aiConfigProperties.getTimeout())
                    .writeTimeout(providerConfig.getWriteTimeout() != null 
                            ? providerConfig.getWriteTimeout() 
                            : aiConfigProperties.getTimeout())
                    .maxRetries(providerConfig.getMaxRetries() != null 
                            ? providerConfig.getMaxRetries() 
                            : aiConfigProperties.getMaxRetries());
        } else {
            // 如果没有配置，使用默认值并记录警告
            log.warn("提供商 {} 未在配置文件中配置，使用默认值", upperProviderName);
            builder.baseUrl("")
                    .apiKey("")
                    .enabled(false)
                    .connectTimeout(aiConfigProperties.getTimeout())
                    .readTimeout(aiConfigProperties.getTimeout())
                    .writeTimeout(aiConfigProperties.getTimeout())
                    .maxRetries(aiConfigProperties.getMaxRetries());
        }
        
        // 根据不同的提供商设置默认模型（如果未配置）
        String defaultModel = providerConfig != null ? providerConfig.getModel() : null;
        if (!StringUtils.hasText(defaultModel)) {
            defaultModel = provider.getDefaultModel();
            builder.defaultModel(defaultModel);
        }
        
        ModelProviderConfig config = builder.build();
        log.info("构建模型配置: provider={}, baseUrl={}, model={}, enabled={}", 
                config.getProviderName(), 
                config.getBaseUrl(), 
                config.getDefaultModel(),
                config.getEnabled());
        
        return config;
    }
    
    /**
     * 获取所有已配置的提供商列表
     */
    public java.util.List<ModelProviderEnum> getConfiguredProviders() {
        return aiConfigProperties.getProviders().entrySet().stream()
                .filter(entry -> {
                    if (entry.getValue() == null 
                            || entry.getValue().getEnabled() == null 
                            || !entry.getValue().getEnabled()
                            || !StringUtils.hasText(entry.getValue().getBaseUrl())) {
                        return false;
                    }
                    
                    // 对于本地DeepSeek（Ollama），apiKey可以为空
                    ModelProviderEnum provider = ModelProviderEnum.from(entry.getKey()).orElse(null);
                    if (provider == ModelProviderEnum.DEEPSEEK) {
                        String baseUrl = entry.getValue().getBaseUrl();
                        boolean isLocalOllama = baseUrl != null 
                                && (baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1"));
                        if (isLocalOllama) {
                            // 本地服务不需要apiKey
                            return true;
                        }
                    }
                    
                    // 其他提供商需要apiKey
                    return StringUtils.hasText(entry.getValue().getApiKey());
                })
                .map(entry -> ModelProviderEnum.from(entry.getKey()).orElse(null))
                .filter(provider -> provider != null)
                .collect(java.util.stream.Collectors.toList());
    }
    
    /**
     * 清除配置缓存
     */
    public void clearCache() {
        configCache.clear();
        log.info("已清除AI模型配置缓存");
    }
}
