package com.codeguardian.enums;

import java.util.Locale;
import java.util.Optional;

public enum ModelProviderEnum {
    QWEN("千问", "qwen3-max"),
    DEEPSEEK("DeepSeek", "deepseek-r1:8b"),
    OPENAI("OpenAI", "gpt-3.5-turbo");

    private final String displayName;
    private final String defaultModel;

    ModelProviderEnum(String displayName, String defaultModel) {
        this.displayName = displayName;
        this.defaultModel = defaultModel;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDefaultModel() {
        return defaultModel;
    }

    public String getCode() {
        return name();
    }

    public static Optional<ModelProviderEnum> from(String providerName) {
        if (providerName == null || providerName.trim().isEmpty()) {
            return Optional.empty();
        }
        String normalized = providerName.trim().toUpperCase(Locale.ROOT);
        try {
            return Optional.of(ModelProviderEnum.valueOf(normalized));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
