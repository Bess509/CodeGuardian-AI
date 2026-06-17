package com.codeguardian.dto.integration;

import lombok.Data;

import java.util.List;

@Data
public class CicdTriggerRequest {
    private String gitUrl;
    private String branch;
    private String commitHash;
    private String baseCommit;
    private String headCommit;
    private String triggerBy;
    private String prNumber;
    private String projectPath;
    private String blockOn;
    private Boolean diffOnly;
    private Boolean inlineComments = false;
    private List<String> changedFiles;
    private String includePaths;
    private String excludePaths;
    private Boolean enableRag = true;
    private Boolean rulesOnly = false;
    private String ruleTemplate;
}
