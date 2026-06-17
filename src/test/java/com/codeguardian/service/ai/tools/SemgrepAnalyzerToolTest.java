package com.codeguardian.service.ai.tools;

import com.codeguardian.entity.Finding;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

class SemgrepAnalyzerToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // Regular tool for JSON parsing test
    private final SemgrepAnalyzerTool regularTool = new SemgrepAnalyzerTool(objectMapper);

    @Test
    @DisplayName("Test Semgrep JSON Parsing Logic")
    void testSemgrepJsonParsing() throws Exception {
        // Sample JSON output from Semgrep
        String jsonOutput = """
            {
              "results": [
                {
                  "check_id": "java.lang.security.audit.sqli.jdbc-sqli",
                  "path": "/tmp/test.java",
                  "start": { "line": 10, "col": 5 },
                  "extra": {
                    "message": "Potential SQL injection detected",
                    "severity": "ERROR"
                  }
                },
                {
                  "check_id": "java.lang.correctness.useless-if",
                  "path": "/tmp/test.java",
                  "start": { "line": 20, "col": 3 },
                  "extra": {
                    "message": "This if statement is always true",
                    "severity": "WARNING"
                  }
                }
              ]
            }
            """;

        List<Finding> findings = regularTool.parseSemgrepOutput(jsonOutput);
        
        assertEquals(2, findings.size());
        
        Finding f1 = findings.get(0);
        assertEquals("java.lang.security.audit.sqli.jdbc-sqli", f1.getTitle());
        assertEquals(com.codeguardian.enums.SeverityEnum.HIGH.getValue(), f1.getSeverity());
        assertEquals(10, f1.getStartLine());
         
        Finding f2 = findings.get(1);
        assertEquals("java.lang.correctness.useless-if", f2.getTitle());
        assertEquals(com.codeguardian.enums.SeverityEnum.MEDIUM.getValue(), f2.getSeverity());
        assertEquals(20, f2.getStartLine());
    }
    
    @Test
    @DisplayName("Test Semgrep JSON Parsing with Empty Results")
    void testSemgrepJsonParsingEmpty() throws Exception {
        String jsonOutput = "{ \"results\": [] }";
        
        List<Finding> findings = regularTool.parseSemgrepOutput(jsonOutput);
        
        assertTrue(findings.isEmpty());
    }
}
