package com.codeguardian.service;

import com.codeguardian.dto.ReviewRequestDTO;

import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

final class ReviewTaskDescriptor {

    private ReviewTaskDescriptor() {
    }

    static String generateTaskName(ReviewRequestDTO request) {
        String type = request.getReviewType() != null ? request.getReviewType().toUpperCase() : "";
        switch (type) {
            case "PROJECT": {
                String base = extractBaseName(request.getProjectPath());
                if (base != null && !base.isEmpty()) return base;
                break;
            }
            case "DIRECTORY": {
                String dirPath = request.getDirectoryPath();
                if (dirPath != null && !dirPath.isBlank()) {
                    String normalized = dirPath.replace('\\', '/');
                    if (normalized.contains("/")) return normalized;
                }
                if (request.getFiles() != null && !request.getFiles().isEmpty()) {
                    List<String> dirs = request.getFiles().stream()
                            .map(f -> f.getPath())
                            .filter(p -> p != null && !p.isBlank())
                            .map(p -> p.replace('\\', '/'))
                            .map(p -> {
                                int last = p.lastIndexOf('/');
                                return last >= 0 ? p.substring(0, last) : "";
                            })
                            .collect(Collectors.toList());
                    String common = computeCommonDir(dirs);
                    if (common != null && !common.isEmpty()) return common;
                }
                if (dirPath != null && !dirPath.isBlank()) return dirPath;
                break;
            }
            case "FILE": {
                String base = extractBaseName(request.getFilePath());
                if (base != null && !base.isEmpty()) return base;
                break;
            }
            case "SNIPPET": {
                String identifier = guessSnippetDisplayName(request.getCodeSnippet(), request.getLanguage());
                if (identifier != null && !identifier.isEmpty()) {
                    return identifier;
                }
                break;
            }
            case "GIT": {
                if (request.getProjectPath() != null && !request.getProjectPath().trim().isEmpty()) {
                    String base = extractBaseName(request.getProjectPath());
                    if (base != null && !base.isEmpty()) return base;
                }
                String repo = extractRepoNameFromUrl(request.getGitUrl());
                if (repo != null && !repo.isEmpty()) return repo;
                if (request.getGitUrl() != null && !request.getGitUrl().isBlank()) {
                    return request.getGitUrl();
                }
                break;
            }
            default:
                break;
        }

        String prefix = switch (type) {
            case "PROJECT" -> "项目审查";
            case "DIRECTORY" -> "目录审查";
            case "FILE" -> "文件审查";
            case "SNIPPET" -> "代码片段审查";
            case "GIT" -> "Git项目审查";
            default -> "代码审查";
        };
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    static String determineScope(ReviewRequestDTO request) {
        String type = request.getReviewType() != null ? request.getReviewType().toUpperCase() : "UNKNOWN";
        switch (type) {
            case "PROJECT":
                return "整个项目";
            case "DIRECTORY":
                return "指定目录";
            case "FILE":
                return "指定文件";
            case "GIT":
                return "git项目";
            case "SNIPPET":
                return "代码片段";
            default:
                return "代码片段";
        }
    }

    static String guessSnippetDisplayName(String code, String language) {
        if (code == null || code.trim().isEmpty()) return null;
        String lang = language != null ? language.toLowerCase() : "";
        if (lang.contains("java")) {
            Pattern classPat = Pattern.compile("\\b(class|interface|enum)\\s+([A-Za-z_][A-Za-z0-9_]*)");
            Matcher cm = classPat.matcher(code);
            String className = cm.find() ? cm.group(2) : null;
            Pattern methodPat = Pattern.compile("\\b(?:public|protected|private|static|final|synchronized|abstract|native|transient|\\s)+\\s*[\\w<>\\[\\]]+\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
            Matcher mm = methodPat.matcher(code);
            String methodName = mm.find() ? mm.group(1) : null;
            if (methodName != null) return className != null ? className + "." + methodName : methodName;
            if (className != null) return className;
            return null;
        } else if (lang.contains("python")) {
            Pattern classPat = Pattern.compile("\\bclass\\s+([A-Za-z_][A-Za-z0-9_]*)");
            Matcher cm = classPat.matcher(code);
            String className = cm.find() ? cm.group(1) : null;
            Pattern methodPat = Pattern.compile("\\bdef\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
            Matcher mm = methodPat.matcher(code);
            String methodName = mm.find() ? mm.group(1) : null;
            if (methodName != null) return className != null ? className + "." + methodName : methodName;
            if (className != null) return className;
            return null;
        } else if (lang.contains("typescript") || lang.contains("ts") || lang.contains("javascript") || lang.contains("js")) {
            Pattern classPat = Pattern.compile("\\bclass\\s+([A-Za-z_][A-Za-z0-9_]*)");
            Matcher cm = classPat.matcher(code);
            String className = cm.find() ? cm.group(1) : null;
            Pattern funcPat1 = Pattern.compile("\\bfunction\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
            Matcher m1 = funcPat1.matcher(code);
            String methodName = m1.find() ? m1.group(1) : null;
            if (methodName == null) {
                Pattern funcPat2 = Pattern.compile("\\bconst\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*\\(");
                Matcher m2 = funcPat2.matcher(code);
                methodName = m2.find() ? m2.group(1) : null;
            }
            if (methodName != null) return className != null ? className + "." + methodName : methodName;
            if (className != null) return className;
            return null;
        }
        Pattern genericClass = Pattern.compile("\\b(class|interface|enum)\\s+([A-Za-z_][A-Za-z0-9_]*)");
        Matcher gc = genericClass.matcher(code);
        if (gc.find()) return gc.group(2);
        Pattern genericFunc = Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
        Matcher gf = genericFunc.matcher(code);
        return gf.find() ? gf.group(1) : null;
    }

    private static String computeCommonDir(List<String> paths) {
        if (paths == null || paths.isEmpty()) return null;
        List<String[]> segments = paths.stream()
                .map(p -> p.split("/"))
                .collect(Collectors.toList());
        int minLen = segments.stream().mapToInt(a -> a.length).min().orElse(0);
        if (minLen == 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < minLen; i++) {
            String seg = segments.get(0)[i];
            boolean allSame = true;
            for (int j = 1; j < segments.size(); j++) {
                if (!seg.equals(segments.get(j)[i])) { allSame = false; break; }
            }
            if (!allSame) break;
            if (sb.length() > 0) sb.append('/');
            sb.append(seg);
        }
        return sb.toString();
    }

    private static String extractBaseName(String path) {
        if (path == null || path.trim().isEmpty()) return null;
        try {
            return Paths.get(path).getFileName().toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractRepoNameFromUrl(String url) {
        if (url == null || url.trim().isEmpty()) return null;
        try {
            String trimmed = url.trim();
            if (trimmed.endsWith(".git")) trimmed = trimmed.substring(0, trimmed.length() - 4);
            int slash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
            if (slash >= 0 && slash < trimmed.length() - 1) {
                return trimmed.substring(slash + 1);
            }
            return trimmed;
        } catch (Exception e) {
            return null;
        }
    }
}
