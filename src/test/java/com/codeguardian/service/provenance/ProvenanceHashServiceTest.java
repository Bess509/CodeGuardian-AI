package com.codeguardian.service.provenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProvenanceHashServiceTest {

    @Test
    void should_hash_audit_event_timestamps_at_database_microsecond_precision() {
        ProvenanceHashService service = new ProvenanceHashService(new ObjectMapper().findAndRegisterModules());
        LocalDateTime javaTimestamp = LocalDateTime.of(2026, 6, 9, 19, 47, 22, 525_017_900);
        LocalDateTime databaseTimestamp = javaTimestamp.truncatedTo(ChronoUnit.MICROS);

        String javaHash = service.hashAuditEvent(
                245L,
                "TASK_CREATED",
                "SUBMIT",
                "system",
                "Review task created and queued",
                "payload",
                "0".repeat(64),
                javaTimestamp
        );
        String databaseHash = service.hashAuditEvent(
                245L,
                "TASK_CREATED",
                "SUBMIT",
                "system",
                "Review task created and queued",
                "payload",
                "0".repeat(64),
                databaseTimestamp
        );

        assertEquals(javaHash, databaseHash);
    }
}
