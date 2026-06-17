package com.codeguardian.mcp.tools;

import com.codeguardian.mcp.McpServerOptions;
import com.codeguardian.mcp.McpToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ListFilesTool extends ReadOnlyToolSupport {

    public ListFilesTool(McpServerOptions options) {
        super(options);
    }

    @Override
    public String name() {
        return "codeguardian.list_files";
    }

    @Override
    public String title() {
        return "List Files";
    }

    @Override
    public String description() {
        return "Read-only file listing under the configured project root. Returns paths and metadata only.";
    }

    @Override
    public ObjectNode inputSchema(ObjectMapper mapper) {
        ObjectNode schema = schema(mapper);
        ObjectNode properties = (ObjectNode) schema.get("properties");
        properties.set("pathPrefix", property(mapper, "string", "Optional relative path under the MCP root."));
        ObjectNode extensions = property(mapper, "array", "Optional file extensions such as java, ts, or .py.");
        extensions.set("items", property(mapper, "string", "File extension."));
        properties.set("extensions", extensions);
        properties.set("limit", property(mapper, "integer", "Maximum number of files to return."));
        return schema;
    }

    @Override
    public McpToolResult call(JsonNode arguments) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String pathPrefix = stringArg(arguments, "pathPrefix", "");
        int limit = intArg(arguments, "limit", options.defaultLimit(), 1, 500);
        Set<String> extensions = extensionsArg(arguments);
        List<Map<String, Object>> files = paths.walkFiles(pathPrefix, options.maxScanFiles()).stream()
                .filter(path -> matchesExtension(path, extensions))
                .sorted(Comparator.comparing(paths::relative))
                .limit(limit)
                .map(path -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("path", paths.relative(path));
                    item.put("extension", SourceText.extension(path));
                    try {
                        item.put("sizeBytes", Files.size(path));
                        item.put("lastModified", Files.getLastModifiedTime(path).toInstant().toString());
                    } catch (IOException ignored) {
                        item.put("metadataAvailable", false);
                    }
                    return item;
                })
                .toList();
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("root", options.root().toString());
        content.put("pathPrefix", pathPrefix);
        content.put("returnedCount", files.size());
        content.put("limit", limit);
        content.put("files", files);
        content.put("contentPolicy", "paths_and_metadata_only");
        return result(mapper, content);
    }
}
