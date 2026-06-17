package com.codeguardian.service.ai.factory;

import org.junit.jupiter.api.Test;
import com.codeguardian.enums.ModelProviderEnum;
import org.springframework.ai.chat.model.ChatModel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ChatClientFactoryTest {

    @Test
    void should_return_no_provider_when_chat_model_map_is_empty() {
        ChatClientFactory factory = new ChatClientFactory(Map.of());

        assertFalse(factory.hasAvailableProviders());
        assertTrue(factory.getAvailableProviders().isEmpty());
        assertNull(factory.getDefaultProvider());
    }

    @Test
    void should_return_available_providers_in_stable_order_when_models_exist() {
        Map<String, ChatModel> modelMap = new LinkedHashMap<>();
        modelMap.put("OPENAI", mock(ChatModel.class));
        modelMap.put("QWEN", mock(ChatModel.class));
        modelMap.put("DEEPSEEK", mock(ChatModel.class));

        ChatClientFactory factory = new ChatClientFactory(modelMap);

        assertTrue(factory.hasAvailableProviders());
        assertEquals(List.of(ModelProviderEnum.QWEN, ModelProviderEnum.DEEPSEEK, ModelProviderEnum.OPENAI), factory.getAvailableProviders());
        assertEquals(ModelProviderEnum.QWEN, factory.getDefaultProvider());
    }
}
