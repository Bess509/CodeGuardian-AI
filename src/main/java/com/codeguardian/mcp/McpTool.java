package com.codeguardian.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public interface McpTool {

    String name();

    String title();

    String description();

    ObjectNode inputSchema(ObjectMapper objectMapper);

    ObjectNode outputSchema(ObjectMapper objectMapper);

    McpToolResult call(JsonNode arguments) throws Exception;

    default ObjectNode annotations(ObjectMapper objectMapper) {
        ObjectNode annotations = objectMapper.createObjectNode();
        annotations.put("title", title());
        annotations.put("readOnlyHint", true);
        annotations.put("destructiveHint", false);
        annotations.put("idempotentHint", true);
        annotations.put("openWorldHint", false);
        return annotations;
    }

    default ObjectNode definition(ObjectMapper objectMapper) {
        ObjectNode definition = objectMapper.createObjectNode();
        definition.put("name", name());
        definition.put("title", title());
        definition.put("description", description());
        definition.set("inputSchema", inputSchema(objectMapper));
        definition.set("outputSchema", outputSchema(objectMapper));
        definition.set("annotations", annotations(objectMapper));
        return definition;
    }
}
