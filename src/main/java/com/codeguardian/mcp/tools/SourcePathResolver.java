package com.codeguardian.mcp.tools;

import com.codeguardian.mcp.McpServerOptions;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

class SourcePathResolver {

    private static final Set<String> SKIPPED_DIRS = Set.of(
            ".git", ".svn", ".hg", "target", "build", "dist", "out", "node_modules",
            ".idea", ".vscode", ".gradle", ".mvn", "__pycache__"
    );

    private final McpServerOptions options;

    SourcePathResolver(McpServerOptions options) {
        this.options = options;
    }

    Path resolve(String relativePath) {
        String safeRelative = relativePath != null ? relativePath.trim() : "";
        Path root = root();
        Path resolved;
        if (safeRelative.isBlank()) {
            resolved = root;
        } else {
            Path requested = Path.of(safeRelative);
            resolved = requested.isAbsolute()
                    ? requested.toAbsolutePath().normalize()
                    : root.resolve(requested).normalize();
        }
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Path is outside the configured MCP root");
        }
        return resolved;
    }

    String relative(Path path) {
        return root().relativize(path.toAbsolutePath().normalize())
                .toString()
                .replace('\\', '/');
    }

    List<Path> walkFiles(String pathPrefix, int maxFiles) throws IOException {
        Path start = resolve(pathPrefix);
        if (!Files.exists(start)) {
            return List.of();
        }
        if (Files.isRegularFile(start)) {
            return isAllowed(start) ? List.of(start) : List.of();
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(start, FileVisitOption.FOLLOW_LINKS)) {
            stream.filter(Files::isRegularFile)
                    .filter(this::isAllowed)
                    .limit(Math.min(maxFiles, options.maxScanFiles()))
                    .forEach(files::add);
        }
        return files;
    }

    boolean isAllowed(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        Path root = root();
        if (!normalized.startsWith(root)) {
            return false;
        }
        Path relative = root.relativize(normalized);
        for (Path part : relative) {
            if (SKIPPED_DIRS.contains(part.toString())) {
                return false;
            }
        }
        return true;
    }

    private Path root() {
        return options.root().toAbsolutePath().normalize();
    }
}
