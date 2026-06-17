package com.codeguardian.dto.webhook;

import lombok.Data;
import java.util.Map;

@Data
public class GitHubWebhookPayload {
    private String action;
    private Map<String, Object> pull_request;
    private Map<String, Object> repository;
    private Map<String, Object> sender;
    // Simplified payload mapping
}
