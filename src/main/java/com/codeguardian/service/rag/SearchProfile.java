package com.codeguardian.service.rag;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

class SearchProfile {

    private final String language;
    private final Set<String> categories;
    private final Set<String> keywords;

    private SearchProfile(String language, Set<String> categories, Set<String> keywords) {
        this.language = language != null ? language.toLowerCase(Locale.ROOT) : "";
        this.categories = categories;
        this.keywords = keywords;
    }

    static SearchProfile from(String query) {
        return new SearchProfile(
                extractLine(query, "Language:"),
                splitCsv(extractLine(query, "Rule Categories:")),
                splitCsv(extractLine(query, "Risk Keywords:"))
        );
    }

    boolean matchesLanguage(RetrievalCandidate candidate) {
        if (language.isBlank() || language.contains("unknown")) {
            return false;
        }
        String haystack = candidate.searchableText();
        if (language.contains("java")) {
            return haystack.contains("java") || haystack.contains(".java")
                    || haystack.contains("spring") || haystack.contains("alibaba");
        }
        if (language.contains("python")) {
            return haystack.contains("python") || haystack.contains(".py");
        }
        if (language.contains("javascript") || language.contains("typescript")) {
            return haystack.contains("javascript") || haystack.contains("typescript")
                    || haystack.contains(".js") || haystack.contains(".ts");
        }
        return haystack.contains(language);
    }

    boolean matchesCategory(RetrievalCandidate candidate) {
        String category = String.valueOf(candidate.metadata.getOrDefault("category", ""))
                .toLowerCase(Locale.ROOT);
        if (!categories.isEmpty()) {
            for (String wanted : categories) {
                if (!wanted.isBlank() && category.contains(wanted)) {
                    return true;
                }
            }
        }
        return keywords.stream().anyMatch(keyword -> keyword.contains("cwe") || keyword.contains("owasp")
                || keyword.contains("injection") || keyword.contains("secret"))
                && category.contains("security");
    }

    boolean matchesKeyword(RetrievalCandidate candidate) {
        if (keywords.isEmpty()) {
            return false;
        }
        String haystack = candidate.searchableText();
        for (String keyword : keywords) {
            if (keyword.length() >= 3 && haystack.contains(keyword)) {
                return true;
            }
            String compact = keyword.replace("-", " ");
            if (compact.length() >= 3 && haystack.contains(compact)) {
                return true;
            }
        }
        return false;
    }

    private static String extractLine(String query, String prefix) {
        if (query == null || prefix == null) {
            return "";
        }
        String[] lines = query.split("\\R");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                return trimmed.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private static Set<String> splitCsv(String value) {
        Set<String> result = new LinkedHashSet<>();
        if (value == null || value.isBlank()) {
            return result;
        }
        for (String part : value.split(",")) {
            String normalized = part.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isBlank()) {
                result.add(normalized);
            }
        }
        return result;
    }
}
