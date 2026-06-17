package com.codeguardian.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Order(100)
@RequiredArgsConstructor
@Slf4j
public class AppendOnlySchemaGuardInitializer implements CommandLineRunner {

    public static final String GUARD_FUNCTION_NAME = "codeguardian_prevent_append_only_mutation";
    public static final String REVIEW_EVIDENCE_TABLE = "review_evidence";
    public static final String REVIEW_AUDIT_EVENTS_TABLE = "review_audit_events";
    public static final String REVIEW_EVIDENCE_TRIGGER = "trg_review_evidence_append_only";
    public static final String REVIEW_AUDIT_EVENTS_TRIGGER = "trg_review_audit_events_append_only";

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        try {
            for (String statement : appendOnlyGuardStatements()) {
                jdbcTemplate.execute(statement);
            }
            log.info("Append-only database guards are installed for review evidence and audit events.");
        } catch (Exception e) {
            log.warn("Append-only database guards were not installed: {}", e.getMessage());
        }
    }

    static List<String> appendOnlyGuardStatements() {
        return List.of(
                """
                CREATE OR REPLACE FUNCTION codeguardian_prevent_append_only_mutation()
                RETURNS TRIGGER AS $$
                BEGIN
                    RAISE EXCEPTION 'CodeGuardian append-only table % cannot be %', TG_TABLE_NAME, TG_OP
                        USING ERRCODE = 'integrity_constraint_violation';
                    RETURN OLD;
                END;
                $$ LANGUAGE plpgsql
                """,
                "DROP TRIGGER IF EXISTS trg_review_evidence_append_only ON review_evidence",
                """
                CREATE TRIGGER trg_review_evidence_append_only
                    BEFORE UPDATE OR DELETE ON review_evidence
                    FOR EACH ROW
                    EXECUTE FUNCTION codeguardian_prevent_append_only_mutation()
                """,
                "DROP TRIGGER IF EXISTS trg_review_audit_events_append_only ON review_audit_events",
                """
                CREATE TRIGGER trg_review_audit_events_append_only
                    BEFORE UPDATE OR DELETE ON review_audit_events
                    FOR EACH ROW
                    EXECUTE FUNCTION codeguardian_prevent_append_only_mutation()
                """
        );
    }
}
