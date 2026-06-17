package com.codeguardian.service.integration;

import com.codeguardian.entity.Finding;
import com.codeguardian.enums.SeverityEnum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QualityJudgeServiceTest {

    private final QualityJudgeService service = new QualityJudgeService();

    @Test
    void should_return_perfect_score_for_empty_review() {
        QualityJudgeResult result = service.evaluate(List.of());

        assertThat(result.getScore()).isEqualTo(100);
        assertThat(result.getGrade()).isEqualTo("A");
        assertThat(result.getEvidenceMissingRate()).isZero();
    }

    @Test
    void should_penalize_duplicates_and_missing_evidence() {
        Finding first = Finding.builder()
                .id(1L)
                .severity(SeverityEnum.HIGH.getValue())
                .title("SQL injection")
                .location("src/UserDao.java:18")
                .startLine(18)
                .category("SECURITY")
                .grounded(false)
                .evidenceCount(0)
                .build();
        Finding duplicate = Finding.builder()
                .id(2L)
                .severity(SeverityEnum.HIGH.getValue())
                .title("SQL injection")
                .location("src/UserDao.java:18")
                .startLine(18)
                .category("SECURITY")
                .grounded(true)
                .evidenceCount(1)
                .build();

        QualityJudgeResult result = service.evaluate(List.of(first, duplicate));

        assertThat(result.getDuplicateCount()).isEqualTo(1);
        assertThat(result.getEvidenceMissingCount()).isEqualTo(1);
        assertThat(result.getHighRiskCount()).isEqualTo(2);
        assertThat(result.getGroundedHighRiskCount()).isEqualTo(1);
        assertThat(result.getScore()).isLessThan(100);
        assertThat(result.getGrade()).isNotEqualTo("A");
    }
}
