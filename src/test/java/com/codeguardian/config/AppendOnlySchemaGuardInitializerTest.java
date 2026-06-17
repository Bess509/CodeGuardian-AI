package com.codeguardian.config;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AppendOnlySchemaGuardInitializerTest {

    @Test
    void should_install_append_only_triggers_on_startup() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AppendOnlySchemaGuardInitializer initializer = new AppendOnlySchemaGuardInitializer(jdbcTemplate);
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);

        initializer.run();

        verify(jdbcTemplate, times(AppendOnlySchemaGuardInitializer.appendOnlyGuardStatements().size()))
                .execute(captor.capture());
        String sql = String.join("\n", captor.getAllValues());
        assertTrue(sql.contains("codeguardian_prevent_append_only_mutation"));
        assertTrue(sql.contains("trg_review_evidence_append_only"));
        assertTrue(sql.contains("trg_review_audit_events_append_only"));
        assertTrue(sql.contains("BEFORE UPDATE OR DELETE ON review_evidence"));
        assertTrue(sql.contains("BEFORE UPDATE OR DELETE ON review_audit_events"));
        assertTrue(sql.contains("integrity_constraint_violation"));
    }

    @Test
    void should_keep_manual_schema_in_sync_with_append_only_guards() throws Exception {
        String schema = Files.readString(Path.of("database/schema.sql"));
        List<String> expectedTokens = List.of(
                "CREATE OR REPLACE FUNCTION codeguardian_prevent_append_only_mutation()",
                "DROP TRIGGER IF EXISTS trg_review_evidence_append_only ON review_evidence;",
                "CREATE TRIGGER trg_review_evidence_append_only",
                "BEFORE UPDATE OR DELETE ON review_evidence",
                "DROP TRIGGER IF EXISTS trg_review_audit_events_append_only ON review_audit_events;",
                "CREATE TRIGGER trg_review_audit_events_append_only",
                "BEFORE UPDATE OR DELETE ON review_audit_events",
                "EXECUTE FUNCTION codeguardian_prevent_append_only_mutation();"
        );

        for (String token : expectedTokens) {
            assertTrue(schema.contains(token), "schema.sql should contain: " + token);
        }
        assertEquals(2, countOccurrences(schema, "EXECUTE FUNCTION codeguardian_prevent_append_only_mutation();"));
    }

    private int countOccurrences(String value, String token) {
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
    }
}
