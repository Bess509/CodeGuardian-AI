package com.codeguardian.service;

import com.codeguardian.dto.ReviewRequestDTO;
import com.codeguardian.entity.Finding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaReviewGateServiceTest {

    private final JavaReviewGateService service = new JavaReviewGateService();

    @Test
    void should_report_compile_error_for_invalid_java_snippet() {
        ReviewRequestDTO request = ReviewRequestDTO.builder()
                .reviewType("SNIPPET")
                .language("Java")
                .build();

        List<Finding> findings = service.checkSingleScope(request, "system.println(\"hello\");", "SNIPPET");

        assertEquals(1, findings.size());
        assertEquals("Java compilation failed", findings.get(0).getTitle());
        assertTrue(findings.get(0).getDescription().contains("system"));
    }

    @Test
    void should_allow_valid_java_snippet() {
        ReviewRequestDTO request = ReviewRequestDTO.builder()
                .reviewType("SNIPPET")
                .language("Java")
                .build();

        List<Finding> findings = service.checkSingleScope(request, "System.out.println(\"hello\");", "SNIPPET");

        assertTrue(findings.isEmpty());
    }
}
