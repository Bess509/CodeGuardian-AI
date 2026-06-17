package com.codeguardian.mcp;

import com.codeguardian.mcp.tools.ListFilesTool;
import com.codeguardian.mcp.tools.ProjectOverviewTool;
import com.codeguardian.mcp.tools.SearchTextTool;
import com.codeguardian.mcp.tools.SummarizeFileTool;

import java.util.List;

final class ReadOnlyCodeTools {

    private ReadOnlyCodeTools() {
    }

    static List<McpTool> create(McpServerOptions options) {
        return List.of(
                new ProjectOverviewTool(options),
                new ListFilesTool(options),
                new SearchTextTool(options),
                new SummarizeFileTool(options)
        );
    }
}
