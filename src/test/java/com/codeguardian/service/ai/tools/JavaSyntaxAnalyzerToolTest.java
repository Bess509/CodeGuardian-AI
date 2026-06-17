package com.codeguardian.service.ai.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

class JavaSyntaxAnalyzerToolTest {

    private final JavaSyntaxAnalyzerTool tool = new JavaSyntaxAnalyzerTool();

    @Test
    @DisplayName("Test analyzing a valid complete Java class")
    void testAnalyzeValidClass() {
        JavaSyntaxAnalyzerTool.Request request = new JavaSyntaxAnalyzerTool.Request();
        request.code = """
            package com.example;
            
            import java.util.List;
            
            public class ValidClass {
                private String name;
                
                public void hello() {
                    System.out.println("Hello");
                }
                
                public int add(int a, int b) {
                    return a + b;
                }
            }
            """;

        JavaSyntaxAnalyzerTool.Response response = tool.apply(request);

        assertTrue(response.valid, "Response should be valid for correct Java code");
        assertEquals(1, response.classCount);
        assertEquals(2, response.methodCount);
        assertTrue(response.problems.isEmpty());
        assertTrue(response.methods.contains("public void hello()"));
        assertTrue(response.methods.contains("public int add(int a, int b)"));
    }

    @Test
    @DisplayName("Test analyzing a valid method snippet (should be wrapped)")
    void testAnalyzeMethodSnippet() {
        JavaSyntaxAnalyzerTool.Request request = new JavaSyntaxAnalyzerTool.Request();
        request.code = """
            public void testMethod() {
                String s = "test";
                System.out.println(s);
            }
            """;

        JavaSyntaxAnalyzerTool.Response response = tool.apply(request);

        assertTrue(response.valid, "Response should be valid for method snippet");
        assertEquals(1, response.methodCount);
        // The wrapped class is filtered out, so classCount might be 0 or handled differently.
        // Current logic filters out "DummyWrapper", so classCount should be 0.
        assertEquals(0, response.classCount); 
    }

    @Test
    @DisplayName("Test analyzing invalid Java code (Syntax Error)")
    void testAnalyzeInvalidCode() {
        JavaSyntaxAnalyzerTool.Request request = new JavaSyntaxAnalyzerTool.Request();
        request.code = "public class Test { public void hello() { System.out.println(\"Hello\" } }"; // Missing parenthesis/semicolon

        JavaSyntaxAnalyzerTool.Response response = tool.apply(request);

        assertFalse(response.valid, "Response should be invalid for syntax error");
        assertFalse(response.problems.isEmpty());
        
        // Verify error message format
        String problem = response.problems.get(0);
        // Should contain location info like "[line X, col Y]"
        assertTrue(problem.matches("\\[line \\d+, col \\d+\\].*"), "Error message should start with location info: " + problem);
        assertFalse(problem.contains("Problem stacktrace"), "Error message should not contain stacktrace");
    }

    @Test
    @DisplayName("Test analyzing Java 14+ Record syntax")
    void testAnalyzeRecord() {
        JavaSyntaxAnalyzerTool.Request request = new JavaSyntaxAnalyzerTool.Request();
        request.code = """
            public record Person(String name, int age) {
                public String description() {
                    return name + " is " + age;
                }
            }
            """;

        JavaSyntaxAnalyzerTool.Response response = tool.apply(request);

        // JavaParser usually supports newer syntax. 
        // If this fails, it means we might need to configure JavaParser version, but default should work for recent versions.
        assertTrue(response.valid, "Should support Java records");
        // Records might be counted as classes or have their own type, but JavaParser treats them as TypeDeclaration.
        // Our tool counts `ClassOrInterfaceDeclaration`. Records are `RecordDeclaration`.
        // Let's see if the tool counts it. 
        // Looking at code: `cu.findAll(ClassOrInterfaceDeclaration.class)`. 
        // RecordDeclaration does NOT extend ClassOrInterfaceDeclaration (it extends TypeDeclaration).
        // So classCount might be 0. This is a potential improvement area, but for now we test current behavior.
        
        // However, the method inside the record SHOULD be found.
        assertEquals(1, response.methodCount);
    }

    @Test
    @DisplayName("Test analyzing empty code")
    void testAnalyzeEmptyCode() {
        JavaSyntaxAnalyzerTool.Request request = new JavaSyntaxAnalyzerTool.Request();
        request.code = "";

        JavaSyntaxAnalyzerTool.Response response = tool.apply(request);

        assertFalse(response.valid);
        assertEquals("未提供代码", response.summary);
    }
}
