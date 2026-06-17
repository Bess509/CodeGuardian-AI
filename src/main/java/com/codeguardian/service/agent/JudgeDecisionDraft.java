package com.codeguardian.service.agent;

import com.codeguardian.enums.SeverityEnum;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class JudgeDecisionDraft {

    static final Set<String> ALLOWED_FIELDS = Set.of(
            "draftId", "decision", "suggestedSeverity", "reason", "requiredEvidenceTypes"
    );
    private static final Set<String> ALLOWED_DECISIONS = Set.of("KEEP", "REVISE", "DROP");
    private static final Set<String> ALLOWED_EVIDENCE_TYPES = Set.of(
            "SOURCE_CODE", "RAG_SNIPPET", "STATIC_SCAN", "RULE_ENGINE", "MODEL_RESPONSE"
    );

    private final String draftId;
    private final String decision;
    private final String suggestedSeverity;
    private final String reason;
    private final List<String> requiredEvidenceTypes;

    private JudgeDecisionDraft(String draftId,
                               String decision,
                               String suggestedSeverity,
                               String reason,
                               List<String> requiredEvidenceTypes) {
        this.draftId = draftId;
        this.decision = decision;
        this.suggestedSeverity = suggestedSeverity;
        this.reason = reason;
        this.requiredEvidenceTypes = requiredEvidenceTypes;
    }

    static JudgeDecisionDraft from(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("judge decision item must be an object");
        }
        var fields = node.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            if (!ALLOWED_FIELDS.contains(field)) {
                throw new IllegalArgumentException("unexpected judge decision field: " + field);
            }
        }
        String decision = requiredText(node, "decision", 16).toUpperCase(Locale.ROOT);
        if (!ALLOWED_DECISIONS.contains(decision)) {
            throw new IllegalArgumentException("invalid judge decision: " + decision);
        }
        return new JudgeDecisionDraft(
                requiredText(node, "draftId", 100),
                decision,
                optionalSeverity(node, "suggestedSeverity"),
                requiredText(node, "reason", 1000),
                evidenceTypes(node.get("requiredEvidenceTypes"))
        );
    }

    JudgeDecision toDecision() {
        return JudgeDecision.builder()
                .draftId(draftId)
                .decision(decision)
                .suggestedSeverity(suggestedSeverity)
                .reason(reason)
                .requiredEvidenceTypes(requiredEvidenceTypes)
                .build();
    }

    private static String requiredText(JsonNode node, String field, int maxLength) {
        String value = optionalText(node, field, maxLength);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required text field: " + field);
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field, int maxLength) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        if (!value.isTextual()) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        return AgentTextSanitizer.sanitize(value.asText(), maxLength);
    }

    private static String optionalSeverity(JsonNode node, String field) {
        String value = optionalText(node, field, 16);
        if (value == null || value.isBlank()) {
            return null;
        }
        for (SeverityEnum severity : SeverityEnum.values()) {
            if (severity.name().equalsIgnoreCase(value)) {
                return severity.name();
            }
        }
        throw new IllegalArgumentException("invalid suggestedSeverity: " + value);
    }

    private static List<String> evidenceTypes(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray() || node.size() > 8) {
            throw new IllegalArgumentException("requiredEvidenceTypes must be an array with at most 8 items");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException("requiredEvidenceTypes item must be a string");
            }
            String value = AgentTextSanitizer.sanitizeToken(item.asText(), 64);
            if (value == null || !ALLOWED_EVIDENCE_TYPES.contains(value)) {
                throw new IllegalArgumentException("invalid evidence type: " + item.asText());
            }
            values.add(value);
        }
        return List.copyOf(values);
    }
}
