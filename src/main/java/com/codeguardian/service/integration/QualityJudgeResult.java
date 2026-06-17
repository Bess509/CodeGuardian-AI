package com.codeguardian.service.integration;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class QualityJudgeResult {
    private int score;
    private int totalFindings;
    private double duplicateRate;
    private double evidenceMissingRate;
    private double highRiskGroundingRate;
    private double p1CoverageRate;
    private double ragHitRate;
    private int duplicateCount;
    private int evidenceMissingCount;
    private int highRiskCount;
    private int groundedHighRiskCount;
    private String grade;
    private String reason;
}
