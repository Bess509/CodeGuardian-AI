package com.codeguardian.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Order(10)
@RequiredArgsConstructor
@Slf4j
public class ReviewReportSchemaInitializer implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        try {
            for (String statement : migrationStatements()) {
                jdbcTemplate.execute(statement);
            }
            log.info("Review report schema compatibility check completed.");
        } catch (Exception e) {
            log.warn("Review report schema compatibility check failed: {}", e.getMessage());
        }
    }

    static List<String> migrationStatements() {
        return List.of(
                "ALTER TABLE IF EXISTS review_reports ADD COLUMN IF NOT EXISTS updated_at timestamp(6)",
                """
                UPDATE review_reports
                SET updated_at = COALESCE(created_at, now())
                WHERE updated_at IS NULL
                """,
                "ALTER TABLE IF EXISTS review_reports ALTER COLUMN updated_at SET DEFAULT now()",
                "ALTER TABLE IF EXISTS review_reports ALTER COLUMN updated_at SET NOT NULL",
                "ALTER TABLE IF EXISTS review_tasks DROP CONSTRAINT IF EXISTS chk_review_tasks_status",
                "ALTER TABLE IF EXISTS review_tasks ADD CONSTRAINT chk_review_tasks_status CHECK (status IN (0, 1, 2, 3, 4))"
        );
    }
}
