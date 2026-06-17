package com.codeguardian.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardDTO {
    private int healthScore;
    private SeverityDistribution problemDistribution;
    private List<ProjectStatDTO> projectStats;
    private QualityJudgeDTO qualityJudge;
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProjectStatDTO {
        private String projectName;
        private int criticalCount;
        private int highCount;
        private int mediumCount;
        private int lowCount;
        private int totalCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SeverityDistribution {
        private int critical;
        private int high;
        private int medium;
        private int low;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QualityJudgeDTO {
        private int score;
        private String grade;
        private String reason;
        private double duplicateRate;
        private double evidenceMissingRate;
        private double highRiskGroundingRate;
        private double p1CoverageRate;
        private double ragHitRate;
    }
}
