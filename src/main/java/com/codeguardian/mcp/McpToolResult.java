package com.codeguardian.mcp;

import com.fasterxml.jackson.databind.JsonNode;

public record McpToolResult(
        String text,
        JsonNode structuredContent,
        boolean error
) {
}
