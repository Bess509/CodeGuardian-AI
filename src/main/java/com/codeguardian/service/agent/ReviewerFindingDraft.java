package com.codeguardian.service.agent;

import com.codeguardian.entity.Finding;
import com.codeguardian.enums.SeverityEnum;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Set;

final class ReviewerFindingDraft {

    static final Set<String> ALLOWED_FIELDS = Set.of(
            "severity", "title", "location", "startLine", "endLine",
            "description", "suggestion", "diff", "category", "confidence",
            "source", "groundingSummary"
    );

    private final Integer severity;
    private final String title;
    private final String location;
    private final Integer startLine;
    private final Integer endLine;
    private final String description;
    private final String suggestion;
    private final String diff;
    private final String category;
    private final Double confidence;
    private final String source;
    private final String groundingSummary;

    private ReviewerFindingDraft(Integer severity,
                                 String title,
                                 String location,
                                 Integer startLine,
                                 Integer endLine,
                                 String description,
                                 String suggestion,
                                 String diff,
                                 String category,
                                 Double confidence,
                                 String source,
                                 String groundingSummary) {
        this.severity = severity;
        this.title = title;
        this.location = location;
        this.startLine = startLine;
        this.endLine = endLine;
        this.description = description;
        this.suggestion = suggestion;
        this.diff = diff;
        this.category = category;
        this.confidence = confidence;
        this.source = source;
        this.groundingSummary = groundingSummary;
    }

    static ReviewerFindingDraft from(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("finding item must be an object");
        }
        var fields = node.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!ALLOWED_FIELDS.contains(field)) {
                throw new IllegalArgumentException("unexpected finding field: " + field);
            }
        }
        Integer severity = parseSeverity(required(node, "severity"));
        Integer startLine = positiveInt(required(node, "startLine"), "startLine");
        Integer endLine = node.has("endLine") && !node.get("endLine").isNull()
                ? positiveInt(node.get("endLine"), "endLine")
                : startLine;
        if (endLine < startLine) {
            throw new IllegalArgumentException("endLine must be greater than or equal to startLine");
        }
        Double confidence = node.has("confidence") && !node.get("confidence").isNull()
                ? boundedDouble(node.get("confidence"), "confidence", 0.0d, 1.0d)
                : null;
        return new ReviewerFindingDraft(
                severity,
                requiredText(node, "title", 200),
                requiredText(node, "location", 500),
                startLine,
                endLine,
                requiredText(node, "description", 2000),
                optionalText(node, "suggestion", 2000),
                optionalText(node, "diff", 4000),
                requiredToken(node, "category", 32),
                confidence,
                optionalText(node, "source", 120),
                optionalText(node, "groundingSummary", 2000)
        );
    }

    Finding toFinding() {
        Finding finding = Finding.builder()
                .severity(severity)
                .title(title)
                .location(location)
                .startLine(startLine)
                .endLine(endLine)
                .description(description)
                .suggestion(suggestion)
                .diff(diff)
                .category(category)
                .source(source)
                .confidence(confidence)
                .groundingSummary(groundingSummary)
                .build();
        AgentTextSanitizer.sanitizeFinding(finding);
        return finding;
    }

    private static JsonNode required(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) {
            throw new IllegalArgumentException("missing required field: " + field);
        }
        return node.get(field);
    }

    private static String requiredText(JsonNode node, String field, int maxLength) {
        String value = optionalText(node, field, maxLength);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required text field: " + field);
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field, int maxLength) {
        if (!node.has(field) || node.get(field).isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        if (!value.isTextual()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return AgentTextSanitizer.sanitize(value.asText(), maxLength);
    }

    private static String requiredToken(JsonNode node, String field, int maxLength) {
        String token = requiredText(node, field, maxLength);
        String sanitized = AgentTextSanitizer.sanitizeToken(token, maxLength);
        if (sanitized == null || sanitized.isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-empty token");
        }
        return sanitized;
    }

    private static Integer positiveInt(JsonNode value, String field) {
        if (!value.canConvertToInt()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        int parsed = value.asInt();
        if (parsed <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return parsed;
    }

    private static Double boundedDouble(JsonNode value, String field, double min, double max) {
        if (!value.isNumber()) {
            throw new IllegalArgumentException(field + " must be a number");
        }
        double parsed = value.asDouble();
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException(field + " out of range");
        }
        return parsed;
    }

    private static Integer parseSeverity(JsonNode value) {
        if (value.canConvertToInt()) {
            int parsed = value.asInt();
            if (parsed >= SeverityEnum.CRITICAL.getValue() && parsed <= SeverityEnum.LOW.getValue()) {
                return parsed;
            }
        }
        if (value.isTextual()) {
            String name = value.asText();
            for (SeverityEnum severity : SeverityEnum.values()) {
                if (severity.name().equalsIgnoreCase(name)) {
                    return severity.getValue();
                }
            }
        }
        throw new IllegalArgumentException("severity must be 0-3 or a known severity name");
    }
}
