package com.codeguardian.service;

import com.codeguardian.config.AIConfigProperties;
import com.codeguardian.entity.Finding;
import com.codeguardian.service.ai.PromptService;
import com.codeguardian.service.ai.factory.ChatClientFactory;
import com.codeguardian.service.ai.output.CodeReviewOutputParser;
import com.codeguardian.service.ai.tool.ToolRegistry;
import com.codeguardian.service.provenance.ProvenanceHashService;
import com.codeguardian.service.rag.KnowledgeBaseService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class AIModelServiceTest {

    @Mock
    private ChatClientFactory chatClientFactory;
    @Mock
    private PromptService promptService;
    @Mock
    private CodeReviewOutputParser outputParser;
    @Mock
    private KnowledgeBaseService knowledgeBaseService;
    @Mock
    private AIConfigProperties aiConfigProperties;
    @Mock
    private ToolRegistry toolRegistry;
    @Mock
    private ProvenanceHashService hashService;

    @InjectMocks
    private AIModelService aiModelService;

    @Test
    void should_return_empty_list_when_ai_disabled() {
        when(aiConfigProperties.getEnabled()).thenReturn(false);
        List<Finding> result = aiModelService.reviewCode("code", "java");
        assertTrue(result.isEmpty());
        verify(chatClientFactory, never()).createChatClient(any());
    }

    @Test
    void should_return_empty_list_when_no_provider_available() {
        when(aiConfigProperties.getEnabled()).thenReturn(true);
        when(chatClientFactory.hasAvailableProviders()).thenReturn(false);
        List<Finding> result = aiModelService.reviewCode("code", "java", "QWEN", true);
        assertTrue(result.isEmpty());
        verify(chatClientFactory, never()).createChatClient(any());
    }

    @Test
    void buildRagQueryText_ShouldUseFindingLineContextInsteadOfFilePrefix() {
        StringBuilder code = new StringBuilder();
        code.append("package demo;\n");
        code.append("import org.springframework.web.bind.annotation.GetMapping;\n");
        code.append("public class DemoController {\n");
        for (int i = 0; i < 40; i++) {
            code.append("    // harmless filler line ").append(i).append(" abcdefghijklmnopqrstuvwxyz\n");
        }
        int passwordLine = code.toString().split("\\R", -1).length;
        code.append("    String password = \"admin123\";\n");
        code.append("}\n");

        Finding seed = Finding.builder()
                .severity(1)
                .title("Hardcoded password")
                .description("A hardcoded password creates a secret leakage risk.")
                .category("SECURITY")
                .startLine(passwordLine)
                .build();

        String query = aiModelService.buildRagQueryText(
                code.toString(),
                "Java",
                "src/main/java/demo/DemoController.java",
                List.of(seed));

        assertTrue(query.contains("File Path: src/main/java/demo/DemoController.java"));
        assertTrue(query.contains("Finding Titles/Messages"));
        assertTrue(query.contains("Risk Keywords"));
        assertTrue(query.contains("hardcoded secret"));
        assertTrue(query.contains("CWE-798"));
        assertTrue(query.contains("String password = \"admin123\";"));
        assertTrue(query.contains("--- target 1 line " + passwordLine + " ---"));
    }
}
