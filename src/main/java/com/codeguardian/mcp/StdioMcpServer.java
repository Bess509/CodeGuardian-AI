package com.codeguardian.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class StdioMcpServer {

    static final String PROTOCOL_VERSION = "2025-06-18";
    private static final int PARSE_ERROR = -32700;
    private static final int INVALID_REQUEST = -32600;
    private static final int METHOD_NOT_FOUND = -32601;
    private static final int INVALID_PARAMS = -32602;
    private static final int INTERNAL_ERROR = -32603;

    private final ObjectMapper objectMapper;
    private final McpToolRegistry toolRegistry;
    private final McpAuditLogger auditLogger;
    private final McpServerOptions options;
    private final String sessionId;

    public StdioMcpServer(ObjectMapper objectMapper,
                          McpToolRegistry toolRegistry,
                          McpAuditLogger auditLogger,
                          McpServerOptions options,
                          String sessionId) {
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
        this.auditLogger = auditLogger;
        this.options = options;
        this.sessionId = sessionId;
    }

    public void run(InputStream input, OutputStream output, PrintStream err) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                Exchange exchange = handleLine(line);
                try {
                    auditLogger.record(exchange.toAuditRecord(line));
                } catch (Exception auditError) {
                    err.println("MCP audit log write failed: " + auditError.getMessage());
                    exchange = errorExchange(exchange.requestId(), exchange.method(), INTERNAL_ERROR,
                            "Audit log write failed", exchange.startedNanos());
                }
                if (exchange.response() != null) {
                    writer.write(objectMapper.writeValueAsString(exchange.response()));
                    writer.newLine();
                    writer.flush();
                }
            }
        }
    }

    private Exchange handleLine(String line) {
        long started = System.nanoTime();
        JsonNode request;
        try {
            request = objectMapper.readTree(line);
        } catch (Exception e) {
            return errorExchange(NullNode.getInstance(), "<parse_error>", PARSE_ERROR, "Parse error", started);
        }
        if (!request.isObject()) {
            return errorExchange(NullNode.getInstance(), "<invalid_request>", INVALID_REQUEST, "Invalid request", started);
        }
        JsonNode requestId = request.get("id");
        String method = request.path("method").asText(null);
        boolean notification = requestId == null;
        if (!"2.0".equals(request.path("jsonrpc").asText()) || method == null || method.isBlank()) {
            return notification
                    ? notificationExchange(method, started)
                    : errorExchange(requestIdOrNull(requestId), method, INVALID_REQUEST, "Invalid JSON-RPC request", started);
        }
        try {
            return switch (method) {
                case "initialize" -> successExchange(requestIdOrNull(requestId), method, initializeResult(request), started);
                case "ping" -> successExchange(requestIdOrNull(requestId), method, objectMapper.createObjectNode(), started);
                case "tools/list" -> successExchange(requestIdOrNull(requestId), method, toolsListResult(), started)
                        .withSummary(Map.of("toolCount", toolRegistry.list().size()));
                case "tools/call" -> callTool(requestIdOrNull(requestId), request, started);
                case "notifications/initialized", "notifications/cancelled" -> notificationExchange(method, started);
                default -> notification
                        ? notificationExchange(method, started)
                        : errorExchange(requestIdOrNull(requestId), method, METHOD_NOT_FOUND, "Method not found: " + method, started);
            };
        } catch (IllegalArgumentException e) {
            return notification
                    ? notificationExchange(method, started)
                    : errorExchange(requestIdOrNull(requestId), method, INVALID_PARAMS, e.getMessage(), started);
        } catch (Exception e) {
            return notification
                    ? notificationExchange(method, started)
                    : errorExchange(requestIdOrNull(requestId), method, INTERNAL_ERROR, e.getMessage(), started);
        }
    }

    private JsonNode requestIdOrNull(JsonNode requestId) {
        return requestId != null ? requestId : NullNode.getInstance();
    }

    private ObjectNode initializeResult(JsonNode request) {
        String requested = request.path("params").path("protocolVersion").asText(PROTOCOL_VERSION);
        String selectedVersion = PROTOCOL_VERSION.equals(requested) ? requested : PROTOCOL_VERSION;
        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", selectedVersion);
        ObjectNode capabilities = objectMapper.createObjectNode();
        ObjectNode tools = objectMapper.createObjectNode();
        tools.put("listChanged", false);
        capabilities.set("tools", tools);
        result.set("capabilities", capabilities);
        ObjectNode serverInfo = objectMapper.createObjectNode();
        serverInfo.put("name", "codeguardian-stdio-mcp");
        serverInfo.put("title", "CodeGuardian Read-only MCP Server");
        serverInfo.put("version", "1.0.0");
        result.set("serverInfo", serverInfo);
        result.put("instructions",
                "This server exposes read-only CodeGuardian tools only. Tool outputs are summarized by default and full source bodies are intentionally omitted. Audit records are appended to "
                        + auditLogger.getAuditLog());
        return result;
    }

    private ObjectNode toolsListResult() {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode tools = objectMapper.createArrayNode();
        for (McpTool tool : toolRegistry.list()) {
            tools.add(tool.definition(objectMapper));
        }
        result.set("tools", tools);
        return result;
    }

    private Exchange callTool(JsonNode requestId, JsonNode request, long started) throws Exception {
        JsonNode params = request.path("params");
        if (!params.isObject()) {
            throw new IllegalArgumentException("params must be an object");
        }
        String toolName = params.path("name").asText(null);
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("params.name is required");
        }
        McpTool tool = toolRegistry.get(toolName);
        if (tool == null) {
            return errorExchange(requestId, "tools/call", INVALID_PARAMS, "Unknown tool: " + toolName, started)
                    .withToolName(toolName);
        }
        JsonNode arguments = params.has("arguments") ? params.get("arguments") : objectMapper.createObjectNode();
        if (!arguments.isObject()) {
            throw new IllegalArgumentException("params.arguments must be an object");
        }
        try {
            McpToolResult toolResult = tool.call(arguments);
            ObjectNode result = objectMapper.createObjectNode();
            ArrayNode content = objectMapper.createArrayNode();
            ObjectNode text = objectMapper.createObjectNode();
            text.put("type", "text");
            text.put("text", toolResult.text());
            content.add(text);
            result.set("content", content);
            result.set("structuredContent", toolResult.structuredContent());
            result.put("isError", toolResult.error());
            return successExchange(requestId, "tools/call", result, started)
                    .withToolName(toolName)
                    .withArgumentsText(objectMapper.writeValueAsString(arguments))
                    .withSuccess(!toolResult.error())
                    .withSummary(Map.of(
                            "toolName", toolName,
                            "isError", toolResult.error(),
                            "contentPolicy", toolResult.structuredContent().path("contentPolicy").asText("summary_only")
                    ));
        } catch (Exception e) {
            ObjectNode result = objectMapper.createObjectNode();
            ArrayNode content = objectMapper.createArrayNode();
            ObjectNode text = objectMapper.createObjectNode();
            text.put("type", "text");
            text.put("text", "Tool execution failed: " + e.getMessage());
            content.add(text);
            result.set("content", content);
            result.put("isError", true);
            return successExchange(requestId, "tools/call", result, started)
                    .withToolName(toolName)
                    .withArgumentsText(objectMapper.writeValueAsString(arguments))
                    .withSuccess(false)
                    .withError(INVALID_PARAMS, e.getMessage())
                    .withSummary(Map.of("toolName", toolName, "isError", true));
        }
    }

    private Exchange successExchange(JsonNode requestId, String method, JsonNode result, long startedNanos) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", requestId != null ? requestId : NullNode.getInstance());
        response.set("result", result);
        return new Exchange(requestId, method, response, startedNanos, true);
    }

    private Exchange errorExchange(JsonNode requestId, String method, int code, String message, long startedNanos) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", requestId != null ? requestId : NullNode.getInstance());
        ObjectNode error = objectMapper.createObjectNode();
        error.put("code", code);
        error.put("message", message != null ? message : "MCP error");
        response.set("error", error);
        return new Exchange(requestId, method, response, startedNanos, false)
                .withError(code, message);
    }

    private Exchange notificationExchange(String method, long startedNanos) {
        return new Exchange(null, method, null, startedNanos, true);
    }

    private final class Exchange {
        private final JsonNode requestId;
        private final String method;
        private final ObjectNode response;
        private final long startedNanos;
        private boolean success;
        private String toolName;
        private String argumentsText;
        private Integer errorCode;
        private String errorMessage;
        private Map<String, Object> summary = Map.of();

        private Exchange(JsonNode requestId, String method, ObjectNode response, long startedNanos, boolean success) {
            this.requestId = requestId;
            this.method = method;
            this.response = response;
            this.startedNanos = startedNanos;
            this.success = success;
        }

        JsonNode requestId() {
            return requestId;
        }

        String method() {
            return method;
        }

        ObjectNode response() {
            return response;
        }

        long startedNanos() {
            return startedNanos;
        }

        Exchange withToolName(String toolName) {
            this.toolName = toolName;
            return this;
        }

        Exchange withArgumentsText(String argumentsText) {
            this.argumentsText = argumentsText;
            return this;
        }

        Exchange withSuccess(boolean success) {
            this.success = success;
            return this;
        }

        Exchange withError(Integer errorCode, String errorMessage) {
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
            return this;
        }

        Exchange withSummary(Map<String, Object> summary) {
            this.summary = summary != null ? summary : Map.of();
            return this;
        }

        McpAuditLogger.AuditRecord toAuditRecord(String requestText) {
            String responseText;
            try {
                responseText = response != null ? objectMapper.writeValueAsString(response) : "";
            } catch (Exception e) {
                responseText = "";
            }
            Map<String, Object> auditSummary = new LinkedHashMap<>(summary);
            auditSummary.putIfAbsent("root", options.root().toString());
            auditSummary.putIfAbsent("sessionId", sessionId);
            return new McpAuditLogger.AuditRecord(
                    method,
                    requestId,
                    toolName,
                    success,
                    (System.nanoTime() - startedNanos) / 1_000_000,
                    requestText,
                    responseText,
                    argumentsText,
                    errorCode,
                    errorMessage,
                    auditSummary
            );
        }
    }
}
