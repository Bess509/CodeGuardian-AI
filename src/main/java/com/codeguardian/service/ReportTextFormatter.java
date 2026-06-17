package com.codeguardian.service;

import com.codeguardian.entity.Finding;
import com.codeguardian.entity.ReviewEvidence;
import com.codeguardian.entity.ReviewTask;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ReportTextFormatter {

    static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Pattern MD_PREFIX = Pattern.compile("^\\s*#{1,6}(?:[:\\s]*)");

    private ReportTextFormatter() {
    }

    static String resolveDisplayName(ReviewTask task, Finding f) {
        String location = f.getLocation() != null ? f.getLocation() : "";
        if (!location.isEmpty()) {
            location = removeMdPrefix(location).trim();
        }

        String displayName = "";
        String lineNumberStr = "";
        if (f.getStartLine() != null) {
            String className = location;
            if (location.contains(":")) {
                String[] parts = location.split(":", -1);
                for (String part : parts) {
                    if (part.contains(".java") || part.contains(".js") || part.contains(".ts") ||
                            part.contains(".py") || part.contains(".cpp") || part.contains(".c")) {
                        className = part.trim();
                        break;
                    }
                }
                if (className.equals(location) && parts.length > 0) {
                    className = parts[0].trim();
                }
            }

            if (!className.matches(".*\\.[a-zA-Z]+.*") && task.getScope() != null) {
                String scope = task.getScope();
                if (scope.contains("/") || scope.contains("\\")) {
                    int slashIdx = Math.max(scope.lastIndexOf('/'), scope.lastIndexOf('\\'));
                    if (slashIdx >= 0 && slashIdx + 1 < scope.length()) {
                        className = scope.substring(slashIdx + 1);
                    } else {
                        className = scope;
                    }
                } else if (scope.matches(".*\\.[a-zA-Z]+.*")) {
                    className = scope;
                }
            }

            displayName = fileNameFromPath(className);
            displayName = removeMdPrefix(displayName).trim();
            if (displayName.matches("^#+$") || displayName.isBlank()) {
                displayName = "代码片段";
            }

            if (displayName.isEmpty() || !displayName.matches(".*\\.[a-zA-Z]+.*")) {
                if (com.codeguardian.enums.ReviewTypeEnum.FILE.getValue().equals(task.getReviewType()) && task.getScope() != null) {
                    String scope = task.getScope();
                    displayName = fileNameFromPath(scope);
                } else {
                    if (location.toLowerCase().contains("class")) {
                        String[] words = location.split("[\\s,]+");
                        for (int i = 0; i < words.length; i++) {
                            if (words[i].equalsIgnoreCase("class") && i + 1 < words.length) {
                                displayName = words[i + 1].replaceAll("[^a-zA-Z0-9_$]", "") + ".java";
                                break;
                            }
                        }
                    }
                    if (displayName.isEmpty() || !displayName.matches(".*\\.[a-zA-Z]+.*")) {
                        displayName = "代码片段";
                    }
                }
            }

            if (f.getEndLine() != null && !f.getEndLine().equals(f.getStartLine())) {
                lineNumberStr = f.getStartLine() + "-" + f.getEndLine();
            } else {
                lineNumberStr = String.valueOf(f.getStartLine());
            }
        } else {
            if (location.contains(":")) {
                String[] parts = location.split(":", 2);
                displayName = removeMdPrefix(parts[0].trim());
                lineNumberStr = parts.length > 1 ? parts[1].trim() : "";
            } else {
                displayName = location;
            }
            displayName = fileNameFromPath(displayName);
            displayName = removeMdPrefix(displayName).trim();
            if (displayName.matches("^#+$") || displayName.isBlank()) {
                displayName = "代码片段";
            }
            if ((displayName.isEmpty() || "代码片段".equals(displayName) || !displayName.matches(".*\\.[a-zA-Z]+.*"))
                    && com.codeguardian.enums.ReviewTypeEnum.FILE.getValue().equals(task.getReviewType()) && task.getScope() != null) {
                String scope = task.getScope();
                displayName = fileNameFromPath(scope);
                if (displayName.isEmpty() && !scope.isEmpty()) {
                    displayName = scope;
                }
            }
        }

        return displayName + (lineNumberStr.isEmpty() ? "" : ":" + lineNumberStr);
    }

    static String severityLabel(Integer severity) {
        if (severity == null) return "低危";
        com.codeguardian.enums.SeverityEnum s = com.codeguardian.enums.SeverityEnum.fromValue(severity);
        return s.getDesc();
    }

    static String badgeIcon(Integer severity) {
        if (severity == null) return "<i class=\"fas fa-chart-bar\"></i>";
        com.codeguardian.enums.SeverityEnum s = com.codeguardian.enums.SeverityEnum.fromValue(severity);
        if (s == com.codeguardian.enums.SeverityEnum.CRITICAL) {
            return "<i class=\"fas fa-shield-alt finding-icon finding-icon-shield\"></i><i class=\"fas fa-check finding-icon finding-icon-check\"></i>";
        }
        if (s == com.codeguardian.enums.SeverityEnum.HIGH) {
            return "<i class=\"fas fa-bug\"></i>";
        }
        if (s == com.codeguardian.enums.SeverityEnum.MEDIUM) {
            return "<i class=\"fas fa-cog\"></i>";
        }
        return "<i class=\"fas fa-chart-bar\"></i>";
    }

    static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    static String shortHash(String hash) {
        if (hash == null || hash.length() <= 12) {
            return hash;
        }
        return hash.substring(0, 12);
    }

    static String metadataValue(ReviewEvidence evidence, String key) {
        if (evidence == null || evidence.getMetadata() == null || key == null) {
            return "";
        }
        Object value = evidence.getMetadata().get(key);
        return value != null ? String.valueOf(value) : "";
    }

    static String displayRetrievalMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "未知检索";
        }
        return switch (mode) {
            case "VECTOR_BM25_FUSED" -> "向量+关键词融合";
            case "VECTOR_ONLY", "VECTOR" -> "向量检索";
            case "BM25_ONLY" -> "关键词检索";
            case "HYBRID_FALLBACK" -> "混合回退";
            default -> mode;
        };
    }

    static String displayAssuranceVerdict(String verdict) {
        if (verdict == null || verdict.isBlank()) {
            return "未知";
        }
        return switch (verdict) {
            case "PROVEN" -> "已证明";
            case "UNPROVEN" -> "未证明";
            case "BLOCKED" -> "已阻断";
            case "UNKNOWN" -> "未知";
            default -> verdict;
        };
    }

    static String generateStatistics(List<Finding> findings) {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total", findings != null ? findings.size() : 0);
        stats.put("critical", countBySeverity(findings, com.codeguardian.enums.SeverityEnum.CRITICAL.getValue()));
        stats.put("high", countBySeverity(findings, com.codeguardian.enums.SeverityEnum.HIGH.getValue()));
        stats.put("medium", countBySeverity(findings, com.codeguardian.enums.SeverityEnum.MEDIUM.getValue()));
        stats.put("low", countBySeverity(findings, com.codeguardian.enums.SeverityEnum.LOW.getValue()));
        return stats.toString();
    }

    static String generateStatisticsMarkdown(List<Finding> findings) {
        return String.format(
                "- **严重**: %d\n- **高**: %d\n- **中**: %d\n- **低**: %d\n\n",
                countBySeverity(findings, com.codeguardian.enums.SeverityEnum.CRITICAL.getValue()),
                countBySeverity(findings, com.codeguardian.enums.SeverityEnum.HIGH.getValue()),
                countBySeverity(findings, com.codeguardian.enums.SeverityEnum.MEDIUM.getValue()),
                countBySeverity(findings, com.codeguardian.enums.SeverityEnum.LOW.getValue())
        );
    }

    static String generateStatisticsHTML(List<Finding> findings) {
        return String.format(
                "<ul><li><strong>严重</strong>: %d</li><li><strong>高</strong>: %d</li><li><strong>中</strong>: %d</li><li><strong>低</strong>: %d</li></ul>\n",
                countBySeverity(findings, com.codeguardian.enums.SeverityEnum.CRITICAL.getValue()),
                countBySeverity(findings, com.codeguardian.enums.SeverityEnum.HIGH.getValue()),
                countBySeverity(findings, com.codeguardian.enums.SeverityEnum.MEDIUM.getValue()),
                countBySeverity(findings, com.codeguardian.enums.SeverityEnum.LOW.getValue())
        );
    }

    static int countBySeverity(List<Finding> findings, Integer severity) {
        if (findings == null) return 0;
        return (int) findings.stream()
                .filter(f -> severity.equals(f.getSeverity()))
                .count();
    }

    static String reviewTypeLabel(Integer type) {
        com.codeguardian.enums.ReviewTypeEnum e = com.codeguardian.enums.ReviewTypeEnum.fromValue(type);
        if (e == com.codeguardian.enums.ReviewTypeEnum.SNIPPET) return "代码片段";
        if (e == com.codeguardian.enums.ReviewTypeEnum.FILE) return "文件";
        if (e == com.codeguardian.enums.ReviewTypeEnum.DIRECTORY) return "目录";
        if (e == com.codeguardian.enums.ReviewTypeEnum.PROJECT) return "项目";
        if (e == com.codeguardian.enums.ReviewTypeEnum.GIT) return "Git";
        return "";
    }

    static String normalizeLineEndings(String s) {
        if (s == null) return "";
        return s.replace("\r\n", "\n").replace("\r", "\n");
    }

    static int countLines(String s) {
        String[] lines = s.split("\n", -1);
        int n = lines.length;
        return n == 0 ? 1 : n;
    }

    static StringBuilder buildLineNumbers(int count) {
        StringBuilder ln = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            ln.append(i);
            if (i < count) ln.append("\n");
        }
        return ln;
    }

    static String detectLanguage(String scopePath) {
        if (scopePath == null) return "language-java";
        String lower = scopePath.toLowerCase();
        if (lower.endsWith(".js")) return "language-javascript";
        if (lower.endsWith(".ts")) return "language-typescript";
        if (lower.endsWith(".py")) return "language-python";
        if (lower.endsWith(".java")) return "language-java";
        return "language-java";
    }

    static String stripLeadingLineNumbers(String code) {
        if (code == null || code.isEmpty()) return code;
        String[] lines = code.split("\\r?\\n", -1);
        StringBuilder out = new StringBuilder();
        Pattern p = Pattern.compile("^\\s*\\d+\\s+(.*)$");
        boolean any = false;
        for (int i = 0; i < lines.length; i++) {
            Matcher m = p.matcher(lines[i]);
            if (m.matches()) {
                out.append(m.group(1));
                any = true;
            } else {
                out.append(lines[i]);
            }
            if (i < lines.length - 1) out.append("\n");
        }
        return any ? out.toString() : code;
    }

    static String removeMdPrefix(String s) {
        if (s == null) return "";
        return MD_PREFIX.matcher(s).replaceFirst("");
    }

    static String fileNameFromPath(String path) {
        if (path == null || path.isBlank()) return "";
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < normalized.length()) {
            return normalized.substring(slash + 1);
        }
        return normalized;
    }
}
