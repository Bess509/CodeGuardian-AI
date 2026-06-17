package com.codeguardian.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

public final class StdioMcpLauncher {

    private StdioMcpLauncher() {
    }

    public static boolean shouldStart(String[] args) {
        if (args == null) {
            return false;
        }
        return Arrays.stream(args)
                .filter(arg -> arg != null)
                .map(arg -> arg.toLowerCase(Locale.ROOT))
                .anyMatch(arg -> arg.equals("--mcp.stdio")
                        || arg.equals("--mcp.stdio=true")
                        || arg.equals("mcp-stdio"));
    }

    public static void run(String[] args, InputStream in, OutputStream out, PrintStream err) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.registerModule(new JavaTimeModule());
            McpServerOptions options = McpServerOptions.fromArgs(args);
            String sessionId = UUID.randomUUID().toString();
            McpAuditLogger auditLogger = new McpAuditLogger(objectMapper, options.auditLog(), sessionId);
            McpToolRegistry registry = McpToolRegistry.readOnlyDefaults(options);
            new StdioMcpServer(objectMapper, registry, auditLogger, options, sessionId).run(in, out, err);
        } catch (Exception e) {
            err.println("Failed to start CodeGuardian stdio MCP server: " + e.getMessage());
        }
    }
}
