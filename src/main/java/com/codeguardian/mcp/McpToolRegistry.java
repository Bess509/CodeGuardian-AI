package com.codeguardian.mcp;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class McpToolRegistry {

    private final Map<String, McpTool> tools = new LinkedHashMap<>();

    public McpToolRegistry(Collection<McpTool> tools) {
        for (McpTool tool : tools) {
            this.tools.put(tool.name(), tool);
        }
    }

    public static McpToolRegistry readOnlyDefaults(McpServerOptions options) {
        return new McpToolRegistry(ReadOnlyCodeTools.create(options));
    }

    public McpTool get(String name) {
        return tools.get(name);
    }

    public List<McpTool> list() {
        return List.copyOf(tools.values());
    }
}
