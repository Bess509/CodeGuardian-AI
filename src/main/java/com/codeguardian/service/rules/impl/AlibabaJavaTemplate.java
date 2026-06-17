package com.codeguardian.service.rules.impl;

import com.codeguardian.service.rules.AbstractJsonRuleTemplate;
import org.springframework.stereotype.Component;

/**
 * 阿里巴巴Java开发手册规范模板
 */
@Component
public class AlibabaJavaTemplate extends AbstractJsonRuleTemplate {

    @Override
    protected String getJsonFilePath() {
        return "rules/alibaba-java.json";
    }

    @Override
    public boolean supports(String language) {
        return "JAVA".equalsIgnoreCase(language);
    }
}
