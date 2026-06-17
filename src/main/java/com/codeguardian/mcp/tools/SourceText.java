package com.codeguardian.mcp.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class SourceText {

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".java", ".kt", ".kts", ".js", ".jsx", ".ts", ".tsx", ".py", ".go", ".rs",
            ".c", ".cc", ".cpp", ".h", ".hpp", ".cs", ".php", ".rb", ".swift",
            ".sql", ".xml", ".json", ".yml", ".yaml", ".properties", ".md", ".txt",
            ".html", ".css", ".scss", ".sh", ".ps1", ".bat", ".gradle"
    );

    private SourceText() {
    }

    static List<String> readLinesCapped(Path file, int maxBytes) throws IOException {
        byte[] bytes;
        try (var input = Files.newInputStream(file)) {
            bytes = input.readNBytes(maxBytes);
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        return text.lines().toList();
    }

    static boolean isTextFile(Path file) {
        if (TEXT_EXTENSIONS.contains(extension(file))) {
            return true;
        }
        try (var input = Files.newInputStream(file)) {
            byte[] sample = input.readNBytes(4096);
            if (sample.length == 0) {
                return true;
            }
            for (byte b : sample) {
                if (b == 0) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static String extension(Path file) {
        String name = file.getFileName() != null ? file.getFileName().toString().toLowerCase(Locale.ROOT) : "";
        int index = name.lastIndexOf('.');
        return index < 0 ? "" : name.substring(index);
    }

    static String language(Path file) {
        return switch (extension(file)) {
            case ".java" -> "Java";
            case ".kt", ".kts" -> "Kotlin";
            case ".js", ".jsx" -> "JavaScript";
            case ".ts", ".tsx" -> "TypeScript";
            case ".py" -> "Python";
            case ".go" -> "Go";
            case ".rs" -> "Rust";
            case ".sql" -> "SQL";
            case ".xml" -> "XML";
            case ".json" -> "JSON";
            case ".yml", ".yaml" -> "YAML";
            case ".md" -> "Markdown";
            default -> "Text";
        };
    }

    static String trim(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    static String sha256File(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        byte[] bytes = digest.digest();
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    static Map<String, Integer> sortCountMap(Map<String, Integer> input) {
        return input.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), LinkedHashMap::putAll);
    }
}
