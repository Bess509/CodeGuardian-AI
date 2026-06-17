package com.codeguardian.service.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentReviewerRoleTest {

    @Test
    void should_default_to_general_when_roles_are_empty() {
        assertEquals(List.of(AgentReviewerRole.GENERAL), AgentReviewerRole.resolve(null));
        assertEquals(List.of(AgentReviewerRole.GENERAL), AgentReviewerRole.resolve(List.of()));
    }

    @Test
    void should_resolve_aliases_and_keep_order_without_duplicates() {
        List<AgentReviewerRole> roles = AgentReviewerRole.resolve(List.of(
                "security",
                "test",
                "company-policy",
                "SECURITY"
        ));

        assertEquals(List.of(
                AgentReviewerRole.SECURITY,
                AgentReviewerRole.TESTING,
                AgentReviewerRole.COMPANY_POLICY
        ), roles);
    }

    @Test
    void should_fallback_to_general_when_no_valid_roles_are_requested() {
        assertEquals(List.of(AgentReviewerRole.GENERAL), AgentReviewerRole.resolve(List.of("unknown")));
    }
}
