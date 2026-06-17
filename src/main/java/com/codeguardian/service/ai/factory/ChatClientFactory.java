package com.codeguardian.service.ai.factory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.codeguardian.enums.ModelProviderEnum;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@Slf4j
@RequiredArgsConstructor
public class ChatClientFactory {

    private static final List<ModelProviderEnum> PROVIDER_ORDER = List.of(
            ModelProviderEnum.QWEN,
            ModelProviderEnum.DEEPSEEK,
            ModelProviderEnum.OPENAI
    );

    private final Map<String, ChatModel> chatModelMap;

    public ChatClient createChatClient(ModelProviderEnum provider) {
        ModelProviderEnum resolvedProvider = resolveProvider(provider)
                .orElseThrow(() -> new IllegalStateException("没有可用的ChatModel"));
        ChatModel chatModel = chatModelMap.get(resolvedProvider.getCode());

        log.debug("为提供商 {} 创建ChatClient", resolvedProvider);

        return ChatClient.builder(chatModel)
                .defaultSystem("你是一个专业的代码审查专家。")
                .build();
    }

    public ChatClient createDefaultChatClient() {
        return createChatClient(null);
    }

    public List<ModelProviderEnum> getAvailableProviders() {
        List<ModelProviderEnum> providers = new ArrayList<>();
        for (ModelProviderEnum provider : PROVIDER_ORDER) {
            if (chatModelMap.containsKey(provider.getCode())) {
                providers.add(provider);
            }
        }
        return providers;
    }

    public boolean hasAvailableProviders() {
        return !chatModelMap.isEmpty();
    }

    public ModelProviderEnum getDefaultProvider() {
        return getAvailableProviders().stream().findFirst().orElse(null);
    }

    private Optional<ModelProviderEnum> resolveProvider(ModelProviderEnum provider) {
        if (provider != null) {
            if (chatModelMap.containsKey(provider.getCode())) {
                return Optional.of(provider);
            }
            log.warn("未找到提供商 {} 的ChatModel，尝试使用其他可用模型", provider.getCode());
        }
        return getAvailableProviders().stream().findFirst();
    }
}
