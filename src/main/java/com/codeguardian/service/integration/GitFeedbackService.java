package com.codeguardian.service.integration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Git平台反馈服务
 * 用于向 GitCode 发送评论或更新状态
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GitFeedbackService {

    @Value("${gitcode.api.base-url:https://api.gitcode.com/api/v5}")
    private String baseUrl;

    @Value("${gitcode.token:}")
    private String token;

    private final RestClient.Builder restClientBuilder;

    public void postComment(String gitUrl, String prNumber, String comment) {
        if (token == null || token.isEmpty()) {
            log.warn("未配置 GitCode Token，跳过发送评论。");
            log.info("模拟发送评论到 {} #{}: {}", gitUrl, prNumber, comment);
            return;
        }

        try {
            String projectPath = extractProjectPath(gitUrl);
            String encodedPath = URLEncoder.encode(projectPath, StandardCharsets.UTF_8);
            
            // GitCode (兼容 GitLab) API: POST /projects/:id/merge_requests/:merge_request_iid/notes
            String uri = String.format("/projects/%s/merge_requests/%s/notes", encodedPath, prNumber);
            
            log.info("正在向 GitCode 发送评论: {}", uri);
            
            restClientBuilder.baseUrl(baseUrl)
                    .build()
                    .post()
                    .uri(uri)
                    .header("PRIVATE-TOKEN", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("body", comment))
                    .retrieve()
                    .toBodilessEntity();
            
            log.info("成功向 GitCode 发送评论");
        } catch (Exception e) {
            log.error("向 GitCode 发送评论失败: {}", e.getMessage());
        }
    }

    public void postInlineComments(String gitUrl, String prNumber, String baseCommit, String headCommit,
                                   String startCommit, List<PrInlineComment> comments) {
        List<PrInlineComment> publishable = comments == null ? List.of() : comments.stream()
                .filter(comment -> Boolean.TRUE.equals(comment.getPublishable()))
                .toList();
        if (publishable.isEmpty()) {
            log.info("No publishable inline comments for {} #{}", gitUrl, prNumber);
            return;
        }
        if (token == null || token.isEmpty()) {
            log.warn("GitCode token is empty; inline comments will be logged only.");
            publishable.forEach(comment -> log.info("Inline comment mock {} #{} {}:{} {}",
                    gitUrl, prNumber, comment.getPath(), comment.getLine(), comment.getTitle()));
            return;
        }

        try {
            String projectPath = extractProjectPath(gitUrl);
            String encodedPath = URLEncoder.encode(projectPath, StandardCharsets.UTF_8);
            String uri = String.format("/projects/%s/merge_requests/%s/discussions", encodedPath, prNumber);
            for (PrInlineComment comment : publishable) {
                Map<String, Object> body = Map.of(
                        "body", comment.getBody(),
                        "position", Map.of(
                                "position_type", "text",
                                "base_sha", nullToHead(baseCommit, headCommit),
                                "start_sha", nullToHead(startCommit, headCommit),
                                "head_sha", nullToHead(headCommit, "HEAD"),
                                "new_path", comment.getPath(),
                                "new_line", comment.getLine()
                        )
                );
                restClientBuilder.baseUrl(baseUrl)
                        .build()
                        .post()
                        .uri(uri)
                        .header("PRIVATE-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();
            }
            log.info("Posted {} GitCode inline comments to #{}", publishable.size(), prNumber);
        } catch (Exception e) {
            log.error("Failed to post GitCode inline comments: {}", e.getMessage());
        }
    }

    public void updateStatus(String gitUrl, String commitHash, String state, String description) {
        if (token == null || token.isEmpty()) {
            log.warn("未配置 GitCode Token，跳过状态更新。");
            log.info("模拟更新状态到 {} {}: {} - {}", gitUrl, commitHash, state, description);
            return;
        }

        try {
            String projectPath = extractProjectPath(gitUrl);
            String encodedPath = URLEncoder.encode(projectPath, StandardCharsets.UTF_8);
            
            // GitCode (兼容 GitLab) API: POST /projects/:id/statuses/:sha
            // 状态映射: pending, running, success, failed, canceled
            String gitLabState = mapToGitLabState(state);
            
            String uri = String.format("/projects/%s/statuses/%s", encodedPath, commitHash);
            
            log.info("正在更新 GitCode 状态: {}", uri);

            restClientBuilder.baseUrl(baseUrl)
                    .build()
                    .post()
                    .uri(uri)
                    .header("PRIVATE-TOKEN", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "state", gitLabState,
                            "description", description,
                            "context", "CodeGuardian AI"
                    ))
                    .retrieve()
                    .toBodilessEntity();

            log.info("成功更新 GitCode 状态");
        } catch (Exception e) {
            log.error("更新 GitCode 状态失败: {}", e.getMessage());
        }
    }
    
    private String extractProjectPath(String gitUrl) {
        // 示例: https://gitcode.com/owner/repo.git -> owner/repo
        String cleanUrl = gitUrl.replace(".git", "");
        if (cleanUrl.startsWith("http")) {
            // 移除协议和域名
            int pathStart = cleanUrl.indexOf("/", cleanUrl.indexOf("://") + 3);
            if (pathStart != -1) {
                return cleanUrl.substring(pathStart + 1);
            }
        }
        return cleanUrl;
    }
    
    private String mapToGitLabState(String state) {
        // 将内部状态映射到 GitLab 状态
        return switch (state.toLowerCase()) {
            case "success", "passed" -> "success";
            case "failure", "failed", "error" -> "failed";
            case "pending", "running" -> "pending";
            default -> "pending";
        };
    }

    private String nullToHead(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }
}
