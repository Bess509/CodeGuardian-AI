package com.codeguardian.mcp.tools;

import com.codeguardian.mcp.McpServerOptions;
import com.codeguardian.mcp.McpToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class SearchTextTool extends ReadOnlyToolSupport {

    public SearchTextTool(McpServerOptions options) {
        super(options);
    }

    @Override
    public String name() {
        return "codeguardian.search_text";
    }

    @Override
    public String title() {
        return "Search Text";
    }

    @Override
    public String description() {
        return "Read-only plain-text search. Returns small capped snippets around matches, not full source files.";
    }

    @Override
    public ObjectNode inputSchema(ObjectMapper mapper) {
        ObjectNode schema = schema(mapper);
        ObjectNode properties = (ObjectNode) schema.get("properties");
        properties.set("query", property(mapper, "string", "Plain text query. Regex is intentionally not supported."));
        properties.set("pathPrefix", property(mapper, "string", "Optional relative path under the MCP root."));
        ObjectNode extensions = property(mapper, "array", "Optional file extensions such as java, ts, or .py.");
        extensions.set("items", property(mapper, "string", "File extension."));
        properties.set("extensions", extensions);
        properties.set("maxResults", property(mapper, "integer", "Maximum matches to return."));
        properties.set("contextLines", property(mapper, "integer", "Context lines around each match, capped at 2."));
        schema.set("required", required(mapper, "query"));
        return schema;
    }

    @Override
    public McpToolResult call(JsonNode arguments) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String query = stringArg(arguments, "query", "");
        if (query.isBlank()) {
            throw new IllegalArgumentException("query is required");
        }
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        String pathPrefix = stringArg(arguments, "pathPrefix", "");
        int maxResults = intArg(arguments, "maxResults", 20, 1, 50);
        int contextLines = intArg(arguments, "contextLines", 1, 0, 2);
        Set<String> extensions = extensionsArg(arguments);
        List<Map<String, Object>> matches = new ArrayList<>();

        for (Path file : paths.walkFiles(pathPrefix, options.maxScanFiles())) {
            if (!matchesExtension(file, extensions) || !SourceText.isTextFile(file)) {
                continue;
            }
            List<String> lines = SourceText.readLinesCapped(file, options.maxFileBytes());
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
                    matches.add(matchItem(file, lines, i, contextLines));
                    if (matches.size() >= maxResults) {
                        return searchResult(mapper, query, pathPrefix, maxResults, contextLines, matches, true);
                    }
                }
            }
        }
        return searchResult(mapper, query, pathPrefix, maxResults, contextLines, matches, false);
    }

    private Map<String, Object> matchItem(Path file, List<String> lines, int index, int contextLines) {
        int start = Math.max(0, index - contextLines);
        int end = Math.min(lines.size() - 1, index + contextLines);
        List<Map<String, Object>> snippet = new ArrayList<>();
        for (int i = start; i <= end; i++) {
            snippet.add(Map.of(
                    "line", i + 1,
                    "text", SourceText.trim(lines.get(i), 180)
            ));
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("path", paths.relative(file));
        item.put("matchLine", index + 1);
        item.put("snippet", snippet);
        return item;
    }

    private McpToolResult searchResult(ObjectMapper mapper,
                                       String query,
                                       String pathPrefix,
                                       int maxResults,
                                       int contextLines,
                                       List<Map<String, Object>> matches,
                                       boolean truncated) throws IOException {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("query", query);
        content.put("pathPrefix", pathPrefix);
        content.put("returnedCount", matches.size());
        content.put("maxResults", maxResults);
        content.put("contextLines", contextLines);
        content.put("truncated", truncated);
        content.put("matches", matches);
        content.put("contentPolicy", "small_capped_snippets_only_no_full_files");
        return result(mapper, content);
    }
}
