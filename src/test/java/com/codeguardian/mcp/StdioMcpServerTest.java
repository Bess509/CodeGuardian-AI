package com.codeguardian.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StdioMcpServerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void should_expose_read_only_tools_summarize_content_and_audit_every_message() throws Exception {
        Path root = tempDir.resolve("repo");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/SecretExample.java"), """
                package demo;

                import java.sql.Connection;

                public class SecretExample {
                    public String leak(String password) {
                        System.out.println("FULL_SOURCE_BODY_SHOULD_NOT_APPEAR");
                        return password;
                    }
                }
                """);
        Path auditLog = tempDir.resolve("audit/mcp-audit.jsonl");
        String input = String.join("\n",
                """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","clientInfo":{"name":"test","version":"1"}}}
                """.trim(),
                """
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """.trim(),
                """
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """.trim(),
                """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"codeguardian.summarize_file","arguments":{"path":"src/SecretExample.java"}}}
                """.trim()
        ) + "\n";

        String stdout = runServer(root, auditLog, input);
        List<JsonNode> responses = parseJsonLines(stdout);

        assertEquals(3, responses.size(), "notifications must not produce stdout responses");
        assertEquals("2025-06-18", responses.get(0).path("result").path("protocolVersion").asText());
        JsonNode tools = responses.get(1).path("result").path("tools");
        assertEquals(4, tools.size());
        for (JsonNode tool : tools) {
            assertTrue(tool.path("name").asText().startsWith("codeguardian."));
            assertTrue(tool.path("annotations").path("readOnlyHint").asBoolean());
            assertFalse(tool.path("annotations").path("destructiveHint").asBoolean());
        }

        String summarizeResponse = responses.get(2).toString();
        assertEquals("summary_only_full_source_omitted_by_default",
                responses.get(2).path("result").path("structuredContent").path("contentPolicy").asText());
        assertFalse(summarizeResponse.contains("FULL_SOURCE_BODY_SHOULD_NOT_APPEAR"));
        assertTrue(summarizeResponse.contains("SecretExample"));
        assertTrue(summarizeResponse.contains("sha256"));

        List<JsonNode> auditEvents = parseJsonLines(Files.readString(auditLog));
        assertEquals(4, auditEvents.size());
        assertEquals("initialize", auditEvents.get(0).path("method").asText());
        assertEquals("notifications/initialized", auditEvents.get(1).path("method").asText());
        assertEquals("tools/list", auditEvents.get(2).path("method").asText());
        assertEquals("tools/call", auditEvents.get(3).path("method").asText());
        assertEquals("codeguardian.summarize_file", auditEvents.get(3).path("toolName").asText());
        assertTrue(auditEvents.get(3).has("argumentsHash"));
        assertFalse(Files.readString(auditLog).contains("FULL_SOURCE_BODY_SHOULD_NOT_APPEAR"));
    }

    @Test
    void should_reject_unknown_write_like_tool_and_audit_failure() throws Exception {
        Path root = tempDir.resolve("repo");
        Files.createDirectories(root);
        Path auditLog = tempDir.resolve("audit/mcp-audit.jsonl");
        String input = """
                {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"codeguardian.write_file","arguments":{"path":"README.md","content":"x"}}}
                """;

        String stdout = runServer(root, auditLog, input);
        List<JsonNode> responses = parseJsonLines(stdout);

        assertEquals(1, responses.size());
        assertEquals(-32602, responses.get(0).path("error").path("code").asInt());
        assertTrue(responses.get(0).path("error").path("message").asText().contains("Unknown tool"));

        List<JsonNode> auditEvents = parseJsonLines(Files.readString(auditLog));
        assertEquals(1, auditEvents.size());
        assertEquals("tools/call", auditEvents.get(0).path("method").asText());
        assertEquals("codeguardian.write_file", auditEvents.get(0).path("toolName").asText());
        assertFalse(auditEvents.get(0).path("success").asBoolean());
    }

    private String runServer(Path root, Path auditLog, String input) throws Exception {
        McpServerOptions options = new McpServerOptions(root, auditLog, 100_000, 100, 10);
        McpAuditLogger auditLogger = new McpAuditLogger(objectMapper, auditLog, "test-session");
        StdioMcpServer server = new StdioMcpServer(
                objectMapper,
                McpToolRegistry.readOnlyDefaults(options),
                auditLogger,
                options,
                "test-session"
        );
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        server.run(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                stdout,
                new PrintStream(stderr, true, StandardCharsets.UTF_8)
        );
        assertEquals("", stderr.toString(StandardCharsets.UTF_8));
        return stdout.toString(StandardCharsets.UTF_8);
    }

    private List<JsonNode> parseJsonLines(String text) {
        return text.lines()
                .filter(line -> !line.isBlank())
                .map(line -> {
                    try {
                        return objectMapper.readTree(line);
                    } catch (Exception e) {
                        throw new AssertionError("Invalid JSON line: " + line, e);
                    }
                })
                .toList();
    }
}
