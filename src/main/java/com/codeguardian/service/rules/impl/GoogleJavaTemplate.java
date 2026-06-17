package com.codeguardian.service.rules.impl;

import com.codeguardian.service.rules.AbstractJsonRuleTemplate;
import org.springframework.stereotype.Component;

/**
 * Google Java Style Guide 规范模板
 */
@Component
public class GoogleJavaTemplate extends AbstractJsonRuleTemplate {

    @Override
    protected String getJsonFilePath() {
        return "rules/google-java.json";
    }

    @Override
    public boolean supports(String language) {
        return "JAVA".equalsIgnoreCase(language);
    }
}
