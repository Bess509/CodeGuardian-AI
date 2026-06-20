package com.codeguardian.controller;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewPageSessionPanelTest {

    @Test
    void review_page_should_render_session_assistant_panel() throws IOException {
        String html = Files.readString(
                Path.of("src/main/resources/templates/review.html"),
                StandardCharsets.UTF_8
        );

        assertTrue(html.contains("id=\"sessionAssistantSection\""));
        assertTrue(html.contains("id=\"sessionMessageList\""));
        assertTrue(html.contains("id=\"sessionMessageInput\""));
        assertTrue(html.contains("id=\"saveSessionMemoryBtn\""));
    }
}
