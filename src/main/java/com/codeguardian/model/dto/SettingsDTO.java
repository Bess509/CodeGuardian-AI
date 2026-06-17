package com.codeguardian.model.dto;

import lombok.Data;
import java.util.Map;

@Data
public class SettingsDTO {
    // General
    private String projectRoot;

    // Rules
    private Map<String, Boolean> ruleCategories; // key: security, performance, etc.
    private Map<String, Integer> ruleWeights;    // key: security, performance, etc.
    private String ruleStandard; // alibaba, google
    private String rulePreset;   // professional, general

    // Scope
    private String includePaths;
    private String excludePaths;

    // Visualization
    private Integer chartHeight;
    private Integer ringThickness;

    // Behavior
    private Integer maxIssues;
    private Integer animationInterval;
}
