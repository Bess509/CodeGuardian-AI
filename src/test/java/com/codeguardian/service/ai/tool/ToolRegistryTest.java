package com.codeguardian.service.ai.tool;

import com.codeguardian.service.ai.tools.JavaSyntaxAnalyzerTool;
import com.codeguardian.service.ai.tools.SemgrepAnalyzerTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

class ToolRegistryTest {

    private ToolRegistry toolRegistry;
    private ApplicationContext applicationContext;
    private JavaSyntaxAnalyzerTool javaSyntaxAnalyzerTool;
    private SemgrepAnalyzerTool semgrepAnalyzerTool;

    @BeforeEach
    void setUp() throws Exception {
        applicationContext = Mockito.mock(ApplicationContext.class);
        
        // Mock tools
        javaSyntaxAnalyzerTool = new JavaSyntaxAnalyzerTool();
        semgrepAnalyzerTool = Mockito.spy(new SemgrepAnalyzerTool(new ObjectMapper()));
        doReturn(new SemgrepAnalyzerTool.Response(true, List.of(), List.of(), "ok"))
                .when(semgrepAnalyzerTool)
                .runSemgrepAnalysis(any(SemgrepAnalyzerTool.Request.class));
        
        // Setup ApplicationContext behavior
        // 1. getBeanNamesForType
        when(applicationContext.getBeanNamesForType(java.util.function.Function.class))
                .thenReturn(new String[]{"javaSyntaxAnalysis", "semgrepAnalysis"});
        
        // 2. getBean
        when(applicationContext.getBean("javaSyntaxAnalysis")).thenReturn(javaSyntaxAnalyzerTool);
        when(applicationContext.getBean("semgrepAnalysis")).thenReturn(semgrepAnalyzerTool);
        
        // 3. findAnnotationOnBean
        org.springframework.context.annotation.Description desc1 = 
            Mockito.mock(org.springframework.context.annotation.Description.class);
        when(desc1.value()).thenReturn("Java Syntax Analyzer");
        when(applicationContext.findAnnotationOnBean("javaSyntaxAnalysis", org.springframework.context.annotation.Description.class))
            .thenReturn(desc1);
            
        org.springframework.context.annotation.Description desc2 = 
            Mockito.mock(org.springframework.context.annotation.Description.class);
        when(desc2.value()).thenReturn("Semgrep Analyzer");
        when(applicationContext.findAnnotationOnBean("semgrepAnalysis", org.springframework.context.annotation.Description.class))
            .thenReturn(desc2);

        toolRegistry = new ToolRegistry(applicationContext, new ObjectMapper());
        toolRegistry.init();
    }

    @Test
    void testToolRegistration() {
        // Verify tools are registered
        Set<String> toolNames = toolRegistry.getToolNames();
        assertTrue(toolNames.contains("javaSyntaxAnalysis"), "Should contain javaSyntaxAnalysis");
        assertTrue(toolNames.contains("semgrepAnalysis"), "Should contain semgrepAnalysis");
        assertEquals(2, toolNames.size());
    }

    @Test
    void testExecuteJavaSyntaxAnalysis() {
        // Prepare arguments
        String jsonArgs = "{\"code\": \"public class Test { }\"}";
        
        // Execute via registry
        Object result = toolRegistry.execute("javaSyntaxAnalysis", jsonArgs);
        
        assertNotNull(result);
        assertTrue(result instanceof JavaSyntaxAnalyzerTool.Response);
        JavaSyntaxAnalyzerTool.Response response = (JavaSyntaxAnalyzerTool.Response) result;
        assertTrue(response.valid);
    }

    @Test
    void testExecuteSemgrepAnalysis() {
        // Prepare arguments
        String jsonArgs = "{\"code\": \"public class Test { }\"}";
        
        // Execute via registry
        Object result = toolRegistry.execute("semgrepAnalysis", jsonArgs);
        
        assertNotNull(result);
        assertTrue(result instanceof SemgrepAnalyzerTool.Response);
        SemgrepAnalyzerTool.Response response = (SemgrepAnalyzerTool.Response) result;
        assertTrue(response.success);
    }
}
