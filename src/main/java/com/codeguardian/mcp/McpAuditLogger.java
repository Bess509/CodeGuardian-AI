package com.codeguardian.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;

public class McpAuditLogger {

    private final ObjectMapper objectMapper;
    private final Path auditLog;
    private final String sessionId;

    public McpAuditLogger(ObjectMapper objectMapper, Path auditLog, String sessionId) {
        this.objectMapper = objectMapper;
        this.auditLog = auditLog.toAbsolutePath().normalize();
        this.sessionId = sessionId;
    }

    public Path getAuditLog() {
        return auditLog;
    }

    public synchronized void record(AuditRecord record) throws IOException {
        Path parent = auditLog.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        ObjectNode node = objectMapper.createObjectNode();
        node.put("timestamp", Instant.now().toString());
        node.put("sessionId", sessionId);
        node.put("transport", "stdio");
        node.put("method", safe(record.method()));
        if (record.requestId() != null) {
            node.set("requestId", record.requestId());
        }
        if (record.toolName() != null && !record.toolName().isBlank()) {
            node.put("toolName", record.toolName());
        }
        node.put("success", record.success());
        node.put("durationMillis", record.durationMillis());
        node.put("requestHash", McpHashes.sha256Hex(record.requestText()));
        node.put("responseHash", McpHashes.sha256Hex(record.responseText()));
        if (record.argumentsText() != null) {
            node.put("argumentsHash", McpHashes.sha256Hex(record.argumentsText()));
        }
        if (record.errorCode() != null) {
            node.put("errorCode", record.errorCode());
        }
        if (record.errorMessage() != null && !record.errorMessage().isBlank()) {
            node.put("errorMessage", trim(record.errorMessage(), 500));
        }
        node.set("summary", objectMapper.valueToTree(record.summary() != null ? record.summary() : Map.of()));

        Files.writeString(
                auditLog,
                objectMapper.writeValueAsString(node) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
    }

    private static String safe(String value) {
        return value != null ? value : "<unknown>";
    }

    private static String trim(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    public record AuditRecord(
            String method,
            JsonNode requestId,
            String toolName,
            boolean success,
            long durationMillis,
            String requestText,
            String responseText,
            String argumentsText,
            Integer errorCode,
            String errorMessage,
            Map<String, Object> summary
    ) {
    }
}
