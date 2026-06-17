package com.codeguardian.service.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;

/**
 * 基于JSON文件的规则模板抽象基类
 */
@Slf4j
public abstract class AbstractJsonRuleTemplate implements RuleTemplate {

    @Autowired
    private ObjectMapper objectMapper;

    private JsonTemplateConfig config;

    /**
     * 子类提供JSON文件路径（相对于classpath）
     */
    protected abstract String getJsonFilePath();

    @PostConstruct
    public void init() {
        if (objectMapper == null) {
            objectMapper = new ObjectMapper();
        }
        String path = getJsonFilePath();
        try {
            ClassPathResource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                log.error("Rule template file not found: {}", path);
                this.config = new JsonTemplateConfig();
                this.config.setName("ERROR");
                this.config.setRules(Collections.emptyList());
                return;
            }
            
            try (InputStream inputStream = resource.getInputStream()) {
                this.config = objectMapper.readValue(inputStream, JsonTemplateConfig.class);
                log.info("Successfully loaded {} rules from template: {}", 
                        config.getRules() != null ? config.getRules().size() : 0, path);
            }
        } catch (IOException e) {
            log.error("Failed to load rules from {}", path, e);
            this.config = new JsonTemplateConfig();
            this.config.setName("ERROR");
            this.config.setRules(Collections.emptyList());
        }
    }

    @Override
    public String getName() {
        return config != null ? config.getName() : "LOADING...";
    }

    @Override
    public String getDescription() {
        return config != null ? config.getDescription() : "";
    }

    @Override
    public List<RuleDefinition> getRules() {
        return config != null && config.getRules() != null ? config.getRules() : Collections.emptyList();
    }

    @Data
    public static class JsonTemplateConfig {
        private String name;
        private String description;
        private List<RuleDefinition> rules;
    }
}
