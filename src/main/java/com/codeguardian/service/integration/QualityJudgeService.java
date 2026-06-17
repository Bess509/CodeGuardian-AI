package com.codeguardian.service.integration;

import com.codeguardian.entity.Finding;
import com.codeguardian.enums.SeverityEnum;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class QualityJudgeService {

    public QualityJudgeResult evaluate(List<Finding> findings) {
        List<Finding> safeFindings = findings != null ? findings : List.of();
        int total = safeFindings.size();
        if (total == 0) {
            return QualityJudgeResult.builder()
                    .score(100)
                    .totalFindings(0)
                    .duplicateRate(0)
                    .evidenceMissingRate(0)
                    .highRiskGroundingRate(1)
                    .p1CoverageRate(1)
                    .ragHitRate(1)
                    .duplicateCount(0)
                    .evidenceMissingCount(0)
                    .highRiskCount(0)
                    .groundedHighRiskCount(0)
                    .grade("A")
                    .reason("No findings; no quality defects detected.")
                    .build();
        }

        Set<String> seen = new HashSet<>();
        int duplicateCount = 0;
        int evidenceMissingCount = 0;
        int groundedCount = 0;
        int highRiskCount = 0;
        int groundedHighRiskCount = 0;

        for (Finding finding : safeFindings) {
            String key = duplicateKey(finding);
            if (!seen.add(key)) {
                duplicateCount++;
            }
            boolean grounded = Boolean.TRUE.equals(finding.getGrounded());
            boolean hasEvidence = finding.getEvidenceCount() != null && finding.getEvidenceCount() > 0;
            if (grounded) {
                groundedCount++;
            }
            if (!hasEvidence && !grounded) {
                evidenceMissingCount++;
            }
            SeverityEnum severity = SeverityEnum.fromValue(finding.getSeverity());
            if (severity == SeverityEnum.CRITICAL || severity == SeverityEnum.HIGH) {
                highRiskCount++;
                if (grounded || hasEvidence) {
                    groundedHighRiskCount++;
                }
            }
        }

        double duplicateRate = ratio(duplicateCount, total);
        double evidenceMissingRate = ratio(evidenceMissingCount, total);
        double highRiskGroundingRate = highRiskCount == 0 ? 1 : ratio(groundedHighRiskCount, highRiskCount);
        double ragHitRate = ratio(groundedCount, total);
        double p1CoverageRate = highRiskGroundingRate;
        int score = clamp(100
                - (int) Math.round(duplicateRate * 25)
                - (int) Math.round(evidenceMissingRate * 35)
                - (int) Math.round((1 - highRiskGroundingRate) * 30)
                - (int) Math.round((1 - ragHitRate) * 10));

        return QualityJudgeResult.builder()
                .score(score)
                .totalFindings(total)
                .duplicateRate(round(duplicateRate))
                .evidenceMissingRate(round(evidenceMissingRate))
                .highRiskGroundingRate(round(highRiskGroundingRate))
                .p1CoverageRate(round(p1CoverageRate))
                .ragHitRate(round(ragHitRate))
                .duplicateCount(duplicateCount)
                .evidenceMissingCount(evidenceMissingCount)
                .highRiskCount(highRiskCount)
                .groundedHighRiskCount(groundedHighRiskCount)
                .grade(grade(score))
                .reason(reason(score, duplicateCount, evidenceMissingCount, highRiskCount, groundedHighRiskCount))
                .build();
    }

    private String duplicateKey(Finding finding) {
        return normalize(finding.getLocation()) + "|"
                + finding.getStartLine() + "|"
                + normalize(finding.getCategory()) + "|"
                + normalize(finding.getTitle());
    }

    private String reason(int score, int duplicateCount, int evidenceMissingCount, int highRiskCount, int groundedHighRiskCount) {
        if (score >= 90) {
            return "Review quality is strong: low duplication and sufficient evidence coverage.";
        }
        if (evidenceMissingCount > 0) {
            return "Review quality is reduced by findings without grounding or evidence.";
        }
        if (duplicateCount > 0) {
            return "Review quality is reduced by duplicate findings.";
        }
        if (highRiskCount > groundedHighRiskCount) {
            return "Review quality is reduced by high-risk findings without evidence coverage.";
        }
        return "Review quality is acceptable but below the target score.";
    }

    private String grade(int score) {
        if (score >= 90) {
            return "A";
        }
        if (score >= 80) {
            return "B";
        }
        if (score >= 70) {
            return "C";
        }
        if (score >= 60) {
            return "D";
        }
        return "F";
    }

    private double ratio(int value, int total) {
        return total == 0 ? 0 : (double) value / total;
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}
