package com.codeguardian.dto.integration;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CicdTaskConfig {
    private Long taskId;
    private String blockOn;
    private String gitUrl;
    private String prNumber;
    private Boolean diffOnly;
    private Boolean inlineComments;
    private List<String> changedFiles;
    private String includePaths;
    private String excludePaths;
    private String baseCommit;
    private String headCommit;
    private String unifiedDiff;
    private Boolean inlineCommentsPosted;
    private String configSource;
}
