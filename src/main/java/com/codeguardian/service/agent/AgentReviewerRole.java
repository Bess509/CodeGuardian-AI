package com.codeguardian.service.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public enum AgentReviewerRole {
    GENERAL("General Reviewer", "balanced review across security, correctness, testing and maintainability risks"),
    SECURITY("Security Reviewer", "security risks such as injection, authentication, authorization, secrets and sensitive data exposure"),
    CORRECTNESS("Correctness Reviewer", "logic errors, null handling, state transitions, data consistency and edge cases"),
    TESTING("Test Reviewer", "missing or weak test coverage for changed behavior and regression-prone paths"),
    MAINTAINABILITY("Maintainability Reviewer", "readability, complexity, naming, duplication and long-term maintenance risks"),
    COMPANY_POLICY("Company Policy Reviewer", "violations of retrieved team standards, internal policies and domain-specific review rules");

    private final String displayName;
    private final String focus;

    AgentReviewerRole(String displayName, String focus) {
        this.displayName = displayName;
        this.focus = focus;
    }

    public String displayName() {
        return displayName;
    }

    public String focus() {
        return focus;
    }

    public static List<AgentReviewerRole> resolve(List<String> requestedRoles) {
        if (requestedRoles == null || requestedRoles.isEmpty()) {
            return List.of(GENERAL);
        }
        Set<AgentReviewerRole> roles = new LinkedHashSet<>();
        for (String requestedRole : requestedRoles) {
            AgentReviewerRole role = from(requestedRole);
            if (role != null) {
                roles.add(role);
            }
        }
        return roles.isEmpty() ? List.of(GENERAL) : new ArrayList<>(roles);
    }

    private static AgentReviewerRole from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
        if ("TEST".equals(normalized)) {
            normalized = "TESTING";
        } else if ("POLICY".equals(normalized)) {
            normalized = "COMPANY_POLICY";
        }
        try {
            return AgentReviewerRole.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
