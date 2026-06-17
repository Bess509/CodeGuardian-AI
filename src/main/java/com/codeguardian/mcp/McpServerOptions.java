package com.codeguardian.mcp;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

public record McpServerOptions(
        Path root,
        Path auditLog,
        int maxFileBytes,
        int maxScanFiles,
        int defaultLimit
) {

    static McpServerOptions fromArgs(String[] args) {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        Path root = pathOption(args, "mcp.root", env("CODEGUARDIAN_MCP_ROOT"), cwd);
        Path auditLog = pathOption(args, "mcp.audit-log", env("CODEGUARDIAN_MCP_AUDIT_LOG"), cwd.resolve("logs/mcp-audit.jsonl"));
        int maxFileBytes = intOption(args, "mcp.max-file-bytes", env("CODEGUARDIAN_MCP_MAX_FILE_BYTES"), 200_000);
        int maxScanFiles = intOption(args, "mcp.max-scan-files", env("CODEGUARDIAN_MCP_MAX_SCAN_FILES"), 5_000);
        int defaultLimit = intOption(args, "mcp.default-limit", env("CODEGUARDIAN_MCP_DEFAULT_LIMIT"), 100);
        return new McpServerOptions(
                root.toAbsolutePath().normalize(),
                auditLog.toAbsolutePath().normalize(),
                Math.max(4_096, maxFileBytes),
                Math.max(100, maxScanFiles),
                Math.max(1, defaultLimit)
        );
    }

    private static Path pathOption(String[] args, String key, String envValue, Path fallback) {
        String value = stringOption(args, key, envValue, null);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Paths.get(value);
    }

    private static int intOption(String[] args, String key, String envValue, int fallback) {
        String value = stringOption(args, key, envValue, null);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String stringOption(String[] args, String key, String envValue, String fallback) {
        String prefix = "--" + key + "=";
        if (args != null) {
            for (String arg : args) {
                if (arg != null && arg.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                    return arg.substring(prefix.length());
                }
            }
        }
        return envValue != null && !envValue.isBlank() ? envValue : fallback;
    }

    private static String env(String name) {
        return System.getenv(name);
    }
}
