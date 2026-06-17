package com.codeguardian.service.integration;

import com.codeguardian.entity.Finding;
import com.codeguardian.enums.SeverityEnum;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PrDiffService {

    private static final Pattern HUNK = Pattern.compile("@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*");

    public Map<String, Set<Integer>> addedLinesByFile(String unifiedDiff) {
        Map<String, Set<Integer>> result = new HashMap<>();
        if (unifiedDiff == null || unifiedDiff.isBlank()) {
            return result;
        }
        String currentFile = null;
        int oldLine = 0;
        int newLine = 0;
        for (String line : unifiedDiff.split("\\R")) {
            if (line.startsWith("+++ b/")) {
                currentFile = normalizePath(line.substring("+++ b/".length()));
                result.computeIfAbsent(currentFile, ignored -> new LinkedHashSet<>());
                continue;
            }
            if (line.startsWith("+++ ")) {
                currentFile = normalizePath(line.substring("+++ ".length()));
                result.computeIfAbsent(currentFile, ignored -> new LinkedHashSet<>());
                continue;
            }
            Matcher hunk = HUNK.matcher(line);
            if (hunk.matches()) {
                oldLine = Integer.parseInt(hunk.group(1));
                newLine = Integer.parseInt(hunk.group(2));
                continue;
            }
            if (currentFile == null || line.startsWith("--- ")) {
                continue;
            }
            if (line.startsWith("+") && !line.startsWith("+++")) {
                result.computeIfAbsent(currentFile, ignored -> new LinkedHashSet<>()).add(newLine);
                newLine++;
            } else if (line.startsWith("-") && !line.startsWith("---")) {
                oldLine++;
            } else {
                oldLine++;
                newLine++;
            }
        }
        return result;
    }

    public List<PrInlineComment> buildInlineComments(List<Finding> findings, String unifiedDiff) {
        Map<String, Set<Integer>> addedLinesByFile = addedLinesByFile(unifiedDiff);
        List<PrInlineComment> comments = new ArrayList<>();
        if (findings == null) {
            return comments;
        }
        for (Finding finding : findings) {
            Location location = parseLocation(finding);
            boolean hasPath = location.path() != null && !location.path().isBlank();
            boolean hasLine = location.line() != null && location.line() > 0;
            boolean inDiff = hasPath && hasLine
                    && addedLinesByFile.getOrDefault(location.path(), Set.of()).contains(location.line());
            comments.add(PrInlineComment.builder()
                    .findingId(finding.getId())
                    .path(location.path())
                    .line(location.line())
                    .side("new")
                    .severity(SeverityEnum.fromValue(finding.getSeverity()).name())
                    .title(finding.getTitle())
                    .body(buildBody(finding))
                    .publishable(inDiff)
                    .reason(inDiff ? "mapped_to_added_diff_line" : reason(hasPath, hasLine, addedLinesByFile.containsKey(location.path())))
                    .build());
        }
        return comments;
    }

    private String buildBody(Finding finding) {
        StringBuilder body = new StringBuilder();
        body.append("**").append(SeverityEnum.fromValue(finding.getSeverity()).name()).append("** ");
        body.append(nullToEmpty(finding.getTitle()));
        if (finding.getDescription() != null && !finding.getDescription().isBlank()) {
            body.append("\n\n").append(finding.getDescription().trim());
        }
        if (finding.getSuggestion() != null && !finding.getSuggestion().isBlank()) {
            body.append("\n\nSuggestion: ").append(finding.getSuggestion().trim());
        }
        if (finding.getEvidenceHash() != null && !finding.getEvidenceHash().isBlank()) {
            body.append("\n\nEvidence: `").append(finding.getEvidenceHash()).append("`");
        }
        return body.toString();
    }

    private String reason(boolean hasPath, boolean hasLine, boolean fileInDiff) {
        if (!hasPath) {
            return "missing_file_path";
        }
        if (!hasLine) {
            return "missing_line";
        }
        if (!fileInDiff) {
            return "file_not_in_diff";
        }
        return "line_not_added_in_diff";
    }

    private Location parseLocation(Finding finding) {
        String path = artifactUri(finding.getLocation());
        Integer line = finding.getStartLine();
        if (line == null && finding.getLocation() != null) {
            Matcher matcher = Pattern.compile(":(\\d+)(?:\\D|$)").matcher(finding.getLocation());
            if (matcher.find()) {
                line = Integer.parseInt(matcher.group(1));
            }
        }
        return new Location(normalizePath(path), line);
    }

    private String artifactUri(String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        String normalized = location.replace("\\", "/").trim();
        int colon = normalized.indexOf(':');
        return colon > 1 ? normalized.substring(0, colon).trim() : normalized;
    }

    private String normalizePath(String path) {
        if (path == null) {
            return null;
        }
        String normalized = path.replace("\\", "/").trim();
        if (normalized.startsWith("b/")) {
            return normalized.substring(2);
        }
        if (normalized.startsWith("a/")) {
            return normalized.substring(2);
        }
        return normalized;
    }

    private String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    private record Location(String path, Integer line) {
    }
}
