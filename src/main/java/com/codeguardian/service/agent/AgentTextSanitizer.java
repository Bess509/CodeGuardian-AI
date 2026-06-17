package com.codeguardian.service.agent;

import com.codeguardian.entity.Finding;

import java.util.regex.Pattern;

final class AgentTextSanitizer {

    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}&&[^\\r\\n\\t]]");
    private static final Pattern INSTRUCTION_LIKE_LINE = Pattern.compile(
            "(?i).*(ignore|disregard|override|forget)\\s+(all\\s+)?(previous|prior|above|system|developer|instructions).*"
                    + "|.*(system prompt|developer message|jailbreak|prompt injection).*"
                    + "|.*(do not report|hide this finding|drop all|mark everything|return only).*");

    private AgentTextSanitizer() {
    }

    static String sanitize(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String normalized = CONTROL_CHARS.matcher(value).replaceAll("");
        StringBuilder sanitized = new StringBuilder();
        String[] lines = normalized.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String cleanLine = INSTRUCTION_LIKE_LINE.matcher(line).matches()
                    ? "[filtered instruction-like content]"
                    : line;
            if (i > 0) {
                sanitized.append('\n');
            }
            sanitized.append(cleanLine);
        }
        String result = sanitized.toString().trim();
        if (maxLength > 0 && result.length() > maxLength) {
            return result.substring(0, Math.max(0, maxLength - 18)) + "\n... [truncated]";
        }
        return result;
    }

    static void sanitizeFinding(Finding finding) {
        if (finding == null) {
            return;
        }
        finding.setTitle(nonBlank(sanitize(finding.getTitle(), 200), "Untitled finding"));
        finding.setLocation(nonBlank(sanitize(finding.getLocation(), 500), "unknown"));
        finding.setDescription(nonBlank(sanitize(finding.getDescription(), 2000), "No description provided."));
        finding.setSuggestion(sanitize(finding.getSuggestion(), 2000));
        finding.setDiff(sanitize(finding.getDiff(), 4000));
        finding.setCategory(sanitizeToken(finding.getCategory(), 32));
        finding.setSource(sanitize(finding.getSource(), 120));
        finding.setGroundingSummary(sanitize(finding.getGroundingSummary(), 2000));
    }

    static String sanitizeToken(String value, int maxLength) {
        String sanitized = sanitize(value, maxLength);
        if (sanitized == null) {
            return null;
        }
        return sanitized.replaceAll("[^A-Za-z0-9_\\-]", "_");
    }

    private static String nonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }
}
