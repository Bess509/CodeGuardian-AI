package com.codeguardian.service;

import com.codeguardian.dto.ReviewRequestDTO;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

final class ReviewContentResolver {

    private ReviewContentResolver() {
    }

    static List<Path> filterChangedFiles(String rootPath, List<Path> files, ReviewRequestDTO request) {
        if (files == null || files.isEmpty()) {
            return files;
        }
        List<String> changedFiles = request.getChangedFiles();
        boolean diffOnly = Boolean.TRUE.equals(request.getDiffOnly());
        if (!diffOnly && (changedFiles == null || changedFiles.isEmpty())) {
            return files;
        }
        if (changedFiles == null || changedFiles.isEmpty()) {
            return List.of();
        }
        Path root = Paths.get(rootPath).toAbsolutePath().normalize();
        java.util.Set<String> changed = changedFiles.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.replace("\\", "/").replaceFirst("^/+", ""))
                .collect(Collectors.toSet());
        return files.stream()
                .filter(path -> {
                    String relative = root.relativize(path.toAbsolutePath().normalize()).toString().replace("\\", "/");
                    return changed.contains(relative);
                })
                .collect(Collectors.toList());
    }

    static String detectLanguage(String pathStr) {
        String fileName = Paths.get(pathStr).getFileName().toString().toLowerCase();
        if (fileName.endsWith(".java")) return "Java";
        if (fileName.endsWith(".py")) return "Python";
        if (fileName.endsWith(".js")) return "JavaScript";
        if (fileName.endsWith(".ts")) return "TypeScript";
        if (fileName.endsWith(".go")) return "Go";
        if (fileName.endsWith(".rs")) return "Rust";
        if (fileName.endsWith(".cpp") || fileName.endsWith(".c") || fileName.endsWith(".h")) return "C/C++";
        return "Unknown";
    }

    static String fetchCodeContent(ReviewRequestDTO request, CodeParserService codeParserService) {
        return switch (request.getReviewType().toUpperCase()) {
            case "SNIPPET" -> request.getCodeSnippet();
            case "FILE" -> {
                if (request.getFiles() != null && !request.getFiles().isEmpty()) {
                    yield request.getFiles().get(0).getContent();
                }
                yield codeParserService.readFile(request.getFilePath());
            }
            default -> throw new IllegalArgumentException("不支持的审查类型: " + request.getReviewType());
        };
    }
}
