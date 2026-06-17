package com.codeguardian.service.rules.impl;

import com.codeguardian.service.rules.AbstractJsonRuleTemplate;
import org.springframework.stereotype.Component;

/**
 * PEP8 Python 规范模板
 */
@Component
public class Pep8PythonTemplate extends AbstractJsonRuleTemplate {

    @Override
    protected String getJsonFilePath() {
        return "rules/pep8-python.json";
    }

    @Override
    public boolean supports(String language) {
        String l = language.toUpperCase();
        return l.equals("PY") || l.equals("PYTHON");
    }
}
