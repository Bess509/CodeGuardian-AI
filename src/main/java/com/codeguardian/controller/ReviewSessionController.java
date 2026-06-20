package com.codeguardian.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.stp.StpUtil;
import com.codeguardian.dto.ReviewMemoryCreateRequestDTO;
import com.codeguardian.dto.ReviewMemoryDTO;
import com.codeguardian.dto.ReviewSessionChatRequestDTO;
import com.codeguardian.dto.ReviewSessionChatResponseDTO;
import com.codeguardian.dto.ReviewSessionCreateRequestDTO;
import com.codeguardian.dto.ReviewSessionDTO;
import com.codeguardian.dto.ReviewSessionMessageDTO;
import com.codeguardian.service.session.ReviewSessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class ReviewSessionController {

    private final ReviewSessionService sessionService;

    @PostMapping
    @SaCheckPermission("REVIEW")
    public ResponseEntity<ReviewSessionDTO> createSession(@Valid @RequestBody ReviewSessionCreateRequestDTO request) {
        return ResponseEntity.ok(sessionService.createSession(currentUserId(), request));
    }

    @GetMapping
    @SaCheckPermission("QUERY")
    public ResponseEntity<Page<ReviewSessionDTO>> listSessions(
            @RequestParam(value = "projectKey", required = false) String projectKey,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("updatedAt").descending());
        return ResponseEntity.ok(sessionService.listSessions(currentUserId(), projectKey, pageable));
    }

    @GetMapping("/{sessionId}/messages")
    @SaCheckPermission("QUERY")
    public ResponseEntity<Page<ReviewSessionMessageDTO>> messages(
            @PathVariable("sessionId") Long sessionId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").ascending());
        return ResponseEntity.ok(sessionService.messages(currentUserId(), sessionId, pageable));
    }

    @PostMapping("/{sessionId}/messages")
    @SaCheckPermission("QUERY")
    public ResponseEntity<ReviewSessionChatResponseDTO> chat(
            @PathVariable("sessionId") Long sessionId,
            @Valid @RequestBody ReviewSessionChatRequestDTO request) {
        return ResponseEntity.ok(sessionService.chat(currentUserId(), sessionId, request));
    }

    @PostMapping("/{sessionId}/memories")
    @SaCheckPermission("REVIEW")
    public ResponseEntity<ReviewMemoryDTO> addMemory(
            @PathVariable("sessionId") Long sessionId,
            @Valid @RequestBody ReviewMemoryCreateRequestDTO request) {
        return ResponseEntity.ok(sessionService.addUserMemory(currentUserId(), sessionId, request));
    }

    private Long currentUserId() {
        return StpUtil.getLoginIdAsLong();
    }
}
