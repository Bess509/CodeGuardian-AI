package com.codeguardian.service;

import com.codeguardian.dto.CustomRuleDTO;
import com.codeguardian.entity.Finding;
import com.codeguardian.service.rules.RuleEngineService;
import com.codeguardian.service.rules.RuleTemplate;
import com.codeguardian.service.rules.impl.AlibabaJavaTemplate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class RuleEngineServiceTest {

    @Test
    public void should_detect_sql_injection_when_java_concat() {
        AlibabaJavaTemplate template = new AlibabaJavaTemplate();
        template.init(); // 手动初始化加载规则
        List<RuleTemplate> templates = List.of(template);
        RuleEngineService engine = new RuleEngineService(templates);
        
        String code = "public class A{\nString sql = \"SELECT * FROM users WHERE name = \" + username;\n}";
        List<Finding> findings = engine.reviewWithTemplate(code, "JAVA", "ALIBABA");
        assertTrue(findings.stream().anyMatch(f -> f.getTitle().contains("SQL")));
        assertTrue(findings.stream().anyMatch(f -> f.getTitle().contains("SQL")
                && "SECURITY".equals(f.getCategory())));
    }

    @Test
    public void should_use_custom_rule_when_template_custom() {
        RuleEngineService engine = new RuleEngineService(List.of());
        String code = "var a = 1;\n";
        CustomRuleDTO rule = CustomRuleDTO.builder()
                .name("禁止var")
                .point("使用let/const代替var")
                .pattern("\\bvar\\b")
                .language("JS")
                .severity("MEDIUM")
                .weight(50)
                .build();
        List<Finding> findings = engine.reviewWithCustom(code, java.util.List.of(rule));
        assertTrue(findings.stream().anyMatch(f -> f.getTitle().contains("禁止var")));
    }
}
