package com.codeguardian.service;

import com.codeguardian.entity.Finding;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class RagQueryBuilder {

    private static final int TARGET_CONTEXT_RADIUS = 300;
    private static final int MAX_SEED_FINDINGS = 5;
    private static final int MAX_SUSPICIOUS_CONTEXTS = 4;
    private static final Pattern SENSITIVE_API_PATTERN = Pattern.compile(
            "(?i)(password|passwd|pwd|secret|token|accessKey|secretKey|apiKey|Runtime\\.getRuntime|\\.exec\\s*\\(|ProcessBuilder|eval\\s*\\(|SELECT\\s+|INSERT\\s+|UPDATE\\s+|DELETE\\s+|Statement\\s*\\(|MD5|SHA1|SHA-1|new\\s+Random\\s*\\(|Math\\.random|@RequestMapping|@GetMapping|@PostMapping|@PutMapping|@DeleteMapping)");

    public RagQuery build(String code, String language, String sourceRef, List<Finding> seedFindings) {
        String safeCode = safe(code);
        String safeLanguage = firstNonBlank(language, "Unknown");
        String safeSourceRef = firstNonBlank(sourceRef, "code-snippet");
        List<Finding> seeds = seedFindings != null ? seedFindings : List.of();

        LinkedHashSet<Integer> targetLines = new LinkedHashSet<>();
        LinkedHashSet<String> riskKeywords = new LinkedHashSet<>();
        LinkedHashSet<String> ruleCategories = new LinkedHashSet<>();
        LinkedHashSet<String> findingSummaries = new LinkedHashSet<>();
        LinkedHashSet<String> changedHunks = new LinkedHashSet<>();

        List<Finding> rankedSeeds = rankSeeds(seeds);
        for (Finding finding : rankedSeeds) {
            collectFindingSignals(finding, targetLines, riskKeywords, ruleCategories, findingSummaries, changedHunks);
        }

        List<Integer> suspiciousLines = findSuspiciousLines(safeCode);
        for (Integer line : suspiciousLines) {
            if (targetLines.size() >= MAX_SUSPICIOUS_CONTEXTS && !rankedSeeds.isEmpty()) {
                break;
            }
            targetLines.add(line);
        }
        riskKeywords.addAll(extractRiskKeywords(safeCode));

        if (targetLines.isEmpty()) {
            targetLines.add(firstNonBoilerplateLine(safeCode));
        }

        String importsAndFrameworks = extractImportsAndFrameworks(safeCode);
        if (importsAndFrameworks.toLowerCase(Locale.ROOT).contains("spring")) {
            riskKeywords.add("Spring web framework");
        }

        String strategy = !rankedSeeds.isEmpty()
                ? "FINDING_TARGET_CONTEXT"
                : (!suspiciousLines.isEmpty() ? "SUSPICIOUS_API_TARGET_CONTEXT" : "FILE_STRUCTURE_TARGET_CONTEXT");

        StringBuilder query = new StringBuilder();
        query.append("RAG Query Strategy: ").append(strategy).append('\n');
        query.append("Language: ").append(safeLanguage).append('\n');
        query.append("File Path: ").append(safeSourceRef).append('\n');
        query.append("Framework/Imports:\n").append(importsAndFrameworks).append('\n');
        appendCollection(query, "Finding Titles/Messages", findingSummaries);
        if (!ruleCategories.isEmpty()) {
            query.append("Rule Categories: ").append(String.join(", ", ruleCategories)).append('\n');
        }
        if (!riskKeywords.isEmpty()) {
            query.append("Risk Keywords: ").append(String.join(", ", riskKeywords)).append('\n');
        }
        appendCollection(query, "Changed Hunk/Diff", changedHunks);
        appendTargetContexts(query, safeCode, targetLines);

        return new RagQuery(query.toString().trim(), strategy, new ArrayList<>(targetLines),
                new ArrayList<>(riskKeywords), new ArrayList<>(ruleCategories));
    }

    private List<Finding> rankSeeds(List<Finding> seeds) {
        return seeds.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator
                        .comparing((Finding f) -> f.getSeverity() != null ? f.getSeverity() : Integer.MAX_VALUE)
                        .thenComparing(f -> f.getStartLine() != null ? f.getStartLine() : Integer.MAX_VALUE))
                .limit(MAX_SEED_FINDINGS)
                .collect(Collectors.toList());
    }

    private void collectFindingSignals(Finding finding,
                                       Set<Integer> targetLines,
                                       Set<String> riskKeywords,
                                       Set<String> ruleCategories,
                                       Set<String> findingSummaries,
                                       Set<String> changedHunks) {
        if (finding.getStartLine() != null && finding.getStartLine() > 0) {
            targetLines.add(finding.getStartLine());
        }
        addIfPresent(ruleCategories, finding.getCategory());
        addIfPresent(ruleCategories, inferCategory(findingText(finding)));
        riskKeywords.addAll(extractRiskKeywords(findingText(finding)));
        String summary = "line " + (finding.getStartLine() != null ? finding.getStartLine() : "?") + ": "
                + firstNonBlank(finding.getTitle(), finding.getDescription());
        addIfPresent(findingSummaries, trim(summary, 220));
        if (finding.getDiff() != null && !finding.getDiff().isBlank()) {
            addIfPresent(changedHunks, trim(finding.getDiff(), 700));
        }
    }

    private void appendCollection(StringBuilder query, String title, Set<String> values) {
        if (values.isEmpty()) {
            return;
        }
        query.append(title).append(":\n");
        values.forEach(value -> query.append("- ").append(value).append('\n'));
    }

    private void appendTargetContexts(StringBuilder query, String code, Set<Integer> targetLines) {
        query.append("Target Contexts (each centered on finding/diff/API line with +/- 300 chars):\n");
        int index = 1;
        for (Integer targetLine : targetLines) {
            query.append("--- target ").append(index++).append(" line ").append(targetLine).append(" ---\n");
            query.append(contextAroundLine(code, targetLine, TARGET_CONTEXT_RADIUS)).append('\n');
        }
    }

    private String extractImportsAndFrameworks(String code) {
        String[] lines = safe(code).split("\\R", -1);
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("package ")
                    || trimmed.startsWith("import ")
                    || trimmed.startsWith("using ")
                    || trimmed.startsWith("#include")
                    || trimmed.startsWith("from ")
                    || trimmed.startsWith("require(")
                    || trimmed.matches("^@(SpringBootApplication|RestController|Controller|Service|Repository|Component|Entity|RequestMapping|GetMapping|PostMapping|PutMapping|DeleteMapping).*")) {
                addIfPresent(values, trim(trimmed, 180));
            }
            if (values.size() >= 35) {
                break;
            }
        }

        String lower = code.toLowerCase(Locale.ROOT);
        if (lower.contains("org.springframework") || lower.contains("@restcontroller") || lower.contains("@requestmapping")) {
            values.add("framework:spring");
        }
        if (lower.contains("mybatis") || lower.contains("@mapper")) {
            values.add("framework:mybatis");
        }
        if (lower.contains("jakarta.persistence") || lower.contains("javax.persistence") || lower.contains("@entity")) {
            values.add("framework:jpa");
        }
        if (lower.contains("lombok") || lower.contains("@data") || lower.contains("@builder")) {
            values.add("framework:lombok");
        }
        return values.isEmpty() ? "(none detected)" : String.join("\n", values);
    }

    private List<Integer> findSuspiciousLines(String code) {
        List<Integer> lines = new ArrayList<>();
        String[] split = safe(code).split("\\R", -1);
        for (int i = 0; i < split.length; i++) {
            if (SENSITIVE_API_PATTERN.matcher(split[i]).find()) {
                lines.add(i + 1);
            }
            if (lines.size() >= MAX_SUSPICIOUS_CONTEXTS) {
                break;
            }
        }
        return lines;
    }

    private Set<String> extractRiskKeywords(String text) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        String safeText = safe(text);
        Matcher matcher = SENSITIVE_API_PATTERN.matcher(safeText);
        while (matcher.find()) {
            addRiskKeyword(keywords, matcher.group());
        }
        addContextualRiskKeywords(keywords, safeText);
        return keywords;
    }

    private void addRiskKeyword(Set<String> keywords, String matched) {
        String lower = matched.toLowerCase(Locale.ROOT);
        if (lower.contains("password") || lower.contains("passwd") || lower.equals("pwd")
                || lower.contains("secret") || lower.contains("token") || lower.contains("accesskey")
                || lower.contains("apikey")) {
            keywords.add("hardcoded secret");
            keywords.add("CWE-798");
        } else if (lower.contains("exec") || lower.contains("processbuilder") || lower.contains("runtime.getruntime")) {
            keywords.add("command injection");
            keywords.add("CWE-78");
        } else if (lower.contains("select") || lower.contains("insert") || lower.contains("update")
                || lower.contains("delete") || lower.contains("statement")) {
            keywords.add("SQL injection");
            keywords.add("CWE-89");
        } else if (lower.contains("md5") || lower.contains("sha1") || lower.contains("sha-1")) {
            keywords.add("weak cryptography");
            keywords.add("CWE-327");
        } else if (lower.contains("random") || lower.contains("math.random")) {
            keywords.add("weak randomness");
            keywords.add("CWE-330");
        } else if (lower.contains("eval")) {
            keywords.add("code injection");
            keywords.add("CWE-94");
        } else if (lower.contains("mapping")) {
            keywords.add("web endpoint");
            keywords.add("OWASP");
        } else {
            keywords.add(trim(matched, 80));
        }
    }

    private void addContextualRiskKeywords(Set<String> keywords, String text) {
        String lowerText = text.toLowerCase(Locale.ROOT);
        if (lowerText.contains("owasp")) {
            keywords.add("OWASP");
        }
        if (lowerText.contains("cwe-")) {
            Matcher cweMatcher = Pattern.compile("(?i)CWE-\\d+").matcher(text);
            while (cweMatcher.find()) {
                keywords.add(cweMatcher.group().toUpperCase(Locale.ROOT));
            }
        }
        if (lowerText.contains("alibaba") || lowerText.contains("p3c")) {
            keywords.add("Alibaba Java");
        }
        if (lowerText.contains("nullpointer") || lowerText.contains("null pointer")) {
            keywords.add("null pointer");
            keywords.add("BUG");
        }
    }

    private String contextAroundLine(String code, int lineNumber, int radius) {
        String safeCode = safe(code);
        if (safeCode.isBlank()) {
            return "";
        }

        int lineStartOffset = offsetForLine(safeCode, Math.max(1, lineNumber));
        int lineEndOffset = lineEndOffset(safeCode, lineStartOffset);
        int startOffset = Math.max(0, lineStartOffset - radius);
        int endOffset = Math.min(safeCode.length(), lineEndOffset + radius);

        while (startOffset > 0 && safeCode.charAt(startOffset - 1) != '\n') {
            startOffset--;
        }
        while (endOffset < safeCode.length() && safeCode.charAt(endOffset) != '\n') {
            endOffset++;
        }

        int startLine = lineNumberAtOffset(safeCode, startOffset);
        String snippet = safeCode.substring(startOffset, endOffset);
        return addLineNumbers(snippet, startLine);
    }

    private String addLineNumbers(String snippet, int startLine) {
        String[] lines = safe(snippet).split("\\R", -1);
        StringBuilder numbered = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i == lines.length - 1 && lines[i].isEmpty()) {
                continue;
            }
            numbered.append(startLine + i).append(": ").append(lines[i]).append('\n');
        }
        return numbered.toString().trim();
    }

    private int offsetForLine(String code, int lineNumber) {
        if (lineNumber <= 1) {
            return 0;
        }
        int line = 1;
        for (int i = 0; i < code.length(); i++) {
            if (code.charAt(i) == '\n') {
                line++;
                if (line == lineNumber) {
                    return i + 1;
                }
            }
        }
        return Math.max(0, code.length() - 1);
    }

    private int lineEndOffset(String code, int lineStartOffset) {
        int newline = code.indexOf('\n', Math.max(0, lineStartOffset));
        return newline >= 0 ? newline : code.length();
    }

    private int lineNumberAtOffset(String code, int offset) {
        int line = 1;
        int bounded = Math.max(0, Math.min(offset, code.length()));
        for (int i = 0; i < bounded; i++) {
            if (code.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private int firstNonBoilerplateLine(String code) {
        String[] lines = safe(code).split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty()
                    || trimmed.startsWith("package ")
                    || trimmed.startsWith("import ")
                    || trimmed.startsWith("//")
                    || trimmed.startsWith("/*")
                    || trimmed.startsWith("*")
                    || trimmed.startsWith("@")) {
                continue;
            }
            return i + 1;
        }
        return 1;
    }

    private String findingText(Finding finding) {
        if (finding == null) {
            return "";
        }
        return String.join("\n",
                safe(finding.getTitle()),
                safe(finding.getDescription()),
                safe(finding.getSuggestion()),
                safe(finding.getLocation()),
                safe(finding.getCategory()));
    }

    private String inferCategory(String text) {
        String lower = safe(text).toLowerCase(Locale.ROOT);
        if (lower.contains("security") || lower.contains("cwe") || lower.contains("owasp")
                || lower.contains("password") || lower.contains("secret") || lower.contains("injection")
                || lower.contains("exec") || lower.contains("eval")) {
            return "SECURITY";
        }
        if (lower.contains("performance") || lower.contains("slow") || lower.contains("memory")) {
            return "PERFORMANCE";
        }
        if (lower.contains("bug") || lower.contains("null") || lower.contains("exception")) {
            return "BUG";
        }
        if (lower.contains("maintain")) {
            return "MAINTAINABILITY";
        }
        return null;
    }

    private void addIfPresent(Set<String> values, String value) {
        if (value != null && !value.isBlank()) {
            values.add(value.trim());
        }
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : (fallback != null ? fallback : "");
    }

    private String safe(String value) {
        return value != null ? value : "";
    }

    private String trim(String value, int maxLength) {
        String safeValue = safe(value).trim();
        if (safeValue.length() <= maxLength) {
            return safeValue;
        }
        return safeValue.substring(0, Math.max(0, maxLength - 15)) + "... (truncated)";
    }
}
