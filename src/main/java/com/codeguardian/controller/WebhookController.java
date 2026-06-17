package com.codeguardian.controller;

import com.codeguardian.dto.ReviewRequestDTO;
import com.codeguardian.dto.ReviewResponseDTO;
import com.codeguardian.dto.webhook.GitHubWebhookPayload;
import com.codeguardian.service.ReviewService;
import com.codeguardian.service.integration.GitFeedbackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Webhook 控制器
 * 处理 GitHub/GitLab 的 Webhook 事件
 */
@RestController
@RequestMapping("/api/v1/webhook")
@RequiredArgsConstructor
@Slf4j
public class WebhookController {

    private final ReviewService reviewService;
    private final GitFeedbackService gitFeedbackService;

    /**
     * 处理 GitCode (GitLab compatible) Webhook
     * 关注事件: Merge Request Hook
     */
    @PostMapping("/gitcode")
    public ResponseEntity<String> handleGitCodeWebhook(
            @RequestHeader(value = "X-Gitlab-Event", defaultValue = "") String eventType,
            @RequestBody Map<String, Object> payload) {
        
        log.info("收到 GitCode Webhook 事件: {}", eventType);

        String objectKind = (String) payload.get("object_kind");
        if (!"merge_request".equals(objectKind)) {
            return ResponseEntity.ok("忽略的事件类型: " + objectKind);
        }

        Map<String, Object> attributes = (Map<String, Object>) payload.get("object_attributes");
        if (attributes == null) {
            return ResponseEntity.badRequest().body("无效的请求体: 缺少 object_attributes");
        }

        String action = (String) attributes.get("action");
        if (!"open".equals(action) && !"update".equals(action) && !"reopen".equals(action)) {
            return ResponseEntity.ok("忽略的操作: " + action);
        }

        // 异步处理
        CompletableFuture.runAsync(() -> processGitCodeMr(payload));

        return ResponseEntity.ok("Webhook 已接收并开始处理。");
    }

    private void processGitCodeMr(Map<String, Object> payload) {
        try {
            Map<String, Object> attributes = (Map<String, Object>) payload.get("object_attributes");
            Map<String, Object> project = (Map<String, Object>) payload.get("project");
            
            if (attributes == null || project == null) return;

            String gitUrl = (String) project.get("git_http_url");
            String htmlUrl = (String) project.get("web_url");
            String branch = (String) attributes.get("source_branch");
            
            Map<String, Object> lastCommit = (Map<String, Object>) attributes.get("last_commit");
            String commitSha = lastCommit != null ? (String) lastCommit.get("id") : null;
            
            Integer mrIid = (Integer) attributes.get("iid");
            String mrTitle = (String) attributes.get("title");

            log.info("正在处理 GitCode MR: {}/merge_requests/{} (branch: {}, commit: {})", htmlUrl, mrIid, branch, commitSha);

            // 1. 设置提交状态为 Pending
            if (commitSha != null) {
                gitFeedbackService.updateStatus(gitUrl, commitSha, "pending", "CodeGuardian AI 审查中...");
            }

            // 2. 触发审查
            ReviewRequestDTO request = ReviewRequestDTO.builder()
                    .reviewType("GIT")
                    .gitUrl(gitUrl)
                    .taskName("MR-" + mrIid + "-" + mrTitle)
                    .build();

            ReviewResponseDTO response = reviewService.createReviewTask(request);

            // 3. 发送初始评论
            gitFeedbackService.postComment(gitUrl, String.valueOf(mrIid), 
                    "🤖 **CodeGuardian AI** 已开始审查此 MR。\n\n" +
                    "任务 ID: `" + response.getTaskId() + "`\n" +
                    "请等待后续审查报告。");

        } catch (Exception e) {
            log.error("处理 GitCode Webhook 失败", e);
        }
    }
}
