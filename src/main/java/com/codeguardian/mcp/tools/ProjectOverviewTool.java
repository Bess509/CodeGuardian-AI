package com.codeguardian.mcp.tools;

import com.codeguardian.mcp.McpServerOptions;
import com.codeguardian.mcp.McpToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ProjectOverviewTool extends ReadOnlyToolSupport {

    public ProjectOverviewTool(McpServerOptions options) {
        super(options);
    }

    @Override
    public String name() {
        return "codeguardian.project_overview";
    }

    @Override
    public String title() {
        return "Project Overview";
    }

    @Override
    public String description() {
        return "Read-only summary of files under the configured project root. Returns counts and representative paths, never file bodies.";
    }

    @Override
    public ObjectNode inputSchema(ObjectMapper mapper) {
        ObjectNode schema = schema(mapper);
        ObjectNode properties = (ObjectNode) schema.get("properties");
        properties.set("pathPrefix", property(mapper, "string", "Optional relative path to summarize under the MCP root."));
        properties.set("maxFiles", property(mapper, "integer", "Maximum files to inspect, capped by server policy."));
        return schema;
    }

    @Override
    public McpToolResult call(JsonNode arguments) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String pathPrefix = stringArg(arguments, "pathPrefix", "");
        int maxFiles = intArg(arguments, "maxFiles", options.defaultLimit() * 20, 1, options.maxScanFiles());
        List<Path> files = paths.walkFiles(pathPrefix, maxFiles);
        Map<String, Integer> byExtension = new HashMap<>();
        Map<String, Integer> byTopDirectory = new HashMap<>();
        List<String> representativePaths = new ArrayList<>();
        long totalBytes = 0;

        for (Path file : files) {
            byExtension.merge(SourceText.extension(file), 1, Integer::sum);
            String relative = paths.relative(file);
            String top = relative.contains("/") ? relative.substring(0, relative.indexOf('/')) : ".";
            byTopDirectory.merge(top, 1, Integer::sum);
            if (representativePaths.size() < 25) {
                representativePaths.add(relative);
            }
            try {
                totalBytes += Files.size(file);
            } catch (IOException ignored) {
                // Best-effort metadata only.
            }
        }

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("root", options.root().toString());
        content.put("pathPrefix", pathPrefix);
        content.put("scannedFileCount", files.size());
        content.put("totalBytes", totalBytes);
        content.put("byExtension", SourceText.sortCountMap(byExtension));
        content.put("byTopDirectory", SourceText.sortCountMap(byTopDirectory));
        content.put("representativePaths", representativePaths);
        content.put("contentPolicy", "summary_only_no_file_bodies");
        return result(mapper, content);
    }
}
