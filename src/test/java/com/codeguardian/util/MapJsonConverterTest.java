package com.codeguardian.util;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapJsonConverterTest {

    @Test
    void should_serialize_java_time_values_in_metadata() {
        MapJsonConverter converter = new MapJsonConverter();
        LocalDateTime completedAt = LocalDateTime.of(2026, 6, 9, 19, 58, 20, 123_456_000);

        String json = converter.convertToDatabaseColumn(Map.of("completedAt", completedAt));
        Map<String, Object> restored = converter.convertToEntityAttribute(json);

        assertTrue(json.contains("2026-06-09T19:58:20.123456"));
        assertEquals("2026-06-09T19:58:20.123456", restored.get("completedAt"));
    }
}
