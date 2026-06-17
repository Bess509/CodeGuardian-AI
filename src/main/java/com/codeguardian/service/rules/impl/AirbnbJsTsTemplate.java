package com.codeguardian.service.rules.impl;

import com.codeguardian.service.rules.AbstractJsonRuleTemplate;
import org.springframework.stereotype.Component;

/**
 * Airbnb JavaScript/TypeScript 规范模板
 */
@Component
public class AirbnbJsTsTemplate extends AbstractJsonRuleTemplate {

    @Override
    protected String getJsonFilePath() {
        return "rules/airbnb-js.json";
    }

    @Override
    public boolean supports(String language) {
        String l = language.toUpperCase();
        return l.equals("JS") || l.equals("TS") || l.equals("JAVASCRIPT") || l.equals("TYPESCRIPT");
    }
}
