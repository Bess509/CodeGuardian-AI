package com.codeguardian.controller;

import com.codeguardian.model.dto.SettingsDTO;
import com.codeguardian.service.SystemConfigService;
import com.codeguardian.service.ai.factory.ChatClientFactory;
import com.codeguardian.enums.ModelProviderEnum;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReviewPageControllerTest {

    @Test
    void should_expose_available_models_when_provider_configured() {
        SystemConfigService configService = mock(SystemConfigService.class);
        ChatClientFactory chatClientFactory = mock(ChatClientFactory.class);
        ReviewPageController controller = new ReviewPageController(configService, chatClientFactory);
        SettingsDTO settings = new SettingsDTO();
        settings.setProjectRoot("/workspace/demo");
        settings.setIncludePaths("src/**");
        settings.setExcludePaths("target/**");
        settings.setMaxIssues(100);
        settings.setRuleStandard("ALIBABA");
        Model model = new ExtendedModelMap();

        when(configService.getSettings()).thenReturn(settings);
        when(chatClientFactory.getAvailableProviders()).thenReturn(List.of(ModelProviderEnum.QWEN, ModelProviderEnum.OPENAI));
        when(chatClientFactory.hasAvailableProviders()).thenReturn(true);
        when(chatClientFactory.getDefaultProvider()).thenReturn(ModelProviderEnum.QWEN);

        String viewName = controller.reviewPage(model, mock(HttpSession.class));

        assertEquals("review", viewName);
        assertEquals(List.of(ModelProviderEnum.QWEN, ModelProviderEnum.OPENAI), model.getAttribute("availableModelProviders"));
        assertEquals(true, model.getAttribute("hasAvailableModelProviders"));
        assertEquals(ModelProviderEnum.QWEN, model.getAttribute("defaultModelProvider"));
    }

    @Test
    void should_hide_model_selection_when_no_provider_configured() {
        SystemConfigService configService = mock(SystemConfigService.class);
        ChatClientFactory chatClientFactory = mock(ChatClientFactory.class);
        ReviewPageController controller = new ReviewPageController(configService, chatClientFactory);
        Model model = new ExtendedModelMap();

        when(configService.getSettings()).thenReturn(new SettingsDTO());
        when(chatClientFactory.getAvailableProviders()).thenReturn(List.of());
        when(chatClientFactory.hasAvailableProviders()).thenReturn(false);
        when(chatClientFactory.getDefaultProvider()).thenReturn(null);

        controller.reviewPage(model, mock(HttpSession.class));

        assertEquals(List.of(), model.getAttribute("availableModelProviders"));
        assertEquals(false, model.getAttribute("hasAvailableModelProviders"));
        assertTrue(model.asMap().containsKey("defaultModelProvider"));
    }
}
