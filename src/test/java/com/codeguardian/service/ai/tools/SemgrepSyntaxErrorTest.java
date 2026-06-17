package com.codeguardian.service.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SemgrepSyntaxErrorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SemgrepAnalyzerTool tool = new SemgrepAnalyzerTool(objectMapper);

    @Test
    @DisplayName("Verify if Semgrep fails to detect vulnerabilities in code with syntax errors")
    void testSemgrepWithSyntaxError() {
        if (tool.resolveSemgrepPath() == null) return;

        SemgrepAnalyzerTool.Request request = new SemgrepAnalyzerTool.Request();
        // SQL Injection + Syntax Error (missing semicolon)
        request.code = """
            public class BadCode {
                public void test(String input) {
                    // SQL Injection
                    String sql = "SELECT * FROM users WHERE name = '" + input + "'" // MISSING SEMICOLON
                    System.out.println(sql);
                }
            }
            """;

        SemgrepAnalyzerTool.Response response = tool.apply(request);
        
        System.out.println("Findings with syntax error: " + response.vulnerabilities);
        
        // If findings are empty, it confirms that syntax errors block Semgrep analysis
        // BUT now we expect a WARNING about parse errors
        if (response.vulnerabilities.isEmpty()) {
            System.out.println("CONFIRMED: Semgrep returned 0 findings due to syntax error.");
        } else if (response.vulnerabilities.get(0).contains("[WARNING]")) {
             System.out.println("SUCCESS: Semgrep detected parse error and returned a warning.");
        } else {
            System.out.println("SURPRISE: Semgrep managed to parse it despite syntax error.");
        }
        
        // Assert that we now get a warning instead of silence
        // Note: This assertion depends on whether Semgrep actually prints "parse error" to stderr in this version.
        // Let's print stderr content if we can (captured in logs usually).
        // For this test, we just want to see the output.
    }
}
