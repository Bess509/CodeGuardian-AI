package com.codeguardian.mcp.tools;

import com.codeguardian.mcp.McpServerOptions;
import com.codeguardian.mcp.McpTool;
import com.codeguardian.mcp.McpToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

abstract class ReadOnlyToolSupport implements McpTool {

    protected final McpServerOptions options;
    protected final SourcePathResolver paths;

    ReadOnlyToolSupport(McpServerOptions options) {
        this.options = options;
        this.paths = new SourcePathResolver(options);
    }

    @Override
    public ObjectNode outputSchema(ObjectMapper objectMapper) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        return schema;
    }

    protected ObjectNode schema(ObjectMapper mapper) {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.set("properties", mapper.createObjectNode());
        return schema;
    }

    protected ObjectNode property(ObjectMapper mapper, String type, String description) {
        ObjectNode property = mapper.createObjectNode();
        property.put("type", type);
        property.put("description", description);
        return property;
    }

    protected ArrayNode required(ObjectMapper mapper, String... names) {
        ArrayNode required = mapper.createArrayNode();
        for (String name : names) {
            required.add(name);
        }
        return required;
    }

    protected McpToolResult result(ObjectMapper mapper, Map<String, Object> content) throws IOException {
        ObjectNode structured = mapper.valueToTree(content);
        String text = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(content);
        return new McpToolResult(text, structured, false);
    }

    protected int intArg(JsonNode args, String name, int fallback, int min, int max) {
        JsonNode value = args != null ? args.get(name) : null;
        int parsed = value != null && value.canConvertToInt() ? value.asInt() : fallback;
        return Math.max(min, Math.min(max, parsed));
    }

    protected String stringArg(JsonNode args, String name, String fallback) {
        JsonNode value = args != null ? args.get(name) : null;
        return value != null && value.isTextual() ? value.asText() : fallback;
    }

    protected Set<String> extensionsArg(JsonNode args) {
        JsonNode value = args != null ? args.get("extensions") : null;
        if (value == null || !value.isArray()) {
            return Set.of();
        }
        Set<String> extensions = new HashSet<>();
        for (JsonNode item : value) {
            if (item.isTextual() && !item.asText().isBlank()) {
                String ext = item.asText().trim().toLowerCase(java.util.Locale.ROOT);
                extensions.add(ext.startsWith(".") ? ext : "." + ext);
            }
        }
        return extensions;
    }

    protected boolean matchesExtension(Path path, Set<String> extensions) {
        return extensions == null || extensions.isEmpty() || extensions.contains(SourceText.extension(path));
    }
}
