package com.codeguardian.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ReviewServiceNameGenerationTest {

    @Test
    void should_extract_class_and_method_when_java_snippet() {
        String code = """
            public class UserService {
                public void findUser(String username) {
                    // ...
                }
            }
        """;
        String name = ReviewService.guessSnippetDisplayName(code, "java");
        assertEquals("UserService.findUser", name);
    }

    @Test
    void should_extract_method_when_java_snippet_without_class() {
        String code = """
            public int sum(int a, int b) {
                return a + b;
            }
        """;
        String name = ReviewService.guessSnippetDisplayName(code, "java");
        assertEquals("sum", name);
    }

    @Test
    void should_extract_class_when_java_snippet_without_method() {
        String code = """
            class App {}
        """;
        String name = ReviewService.guessSnippetDisplayName(code, "java");
        assertEquals("App", name);
    }
}
