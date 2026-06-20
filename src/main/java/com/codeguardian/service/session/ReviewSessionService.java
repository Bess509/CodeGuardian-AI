package com.codeguardian.service.session;

import com.codeguardian.dto.ReviewMemoryCreateRequestDTO;
import com.codeguardian.dto.ReviewMemoryDTO;
import com.codeguardian.dto.ReviewSessionChatRequestDTO;
import com.codeguardian.dto.ReviewSessionChatResponseDTO;
import com.codeguardian.dto.ReviewSessionCreateRequestDTO;
import com.codeguardian.dto.ReviewSessionDTO;
import com.codeguardian.dto.ReviewSessionMessageDTO;
import com.codeguardian.entity.Finding;
import com.codeguardian.entity.ReviewSession;
import com.codeguardian.entity.ReviewSessionMemory;
import com.codeguardian.entity.ReviewSessionMessage;
import com.codeguardian.enums.SeverityEnum;
import com.codeguardian.repository.FindingRepository;
import com.codeguardian.repository.ReviewSessionMessageRepository;
import com.codeguardian.repository.ReviewSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ReviewSessionService {

    private static final int RECENT_MESSAGE_WINDOW = 20;
    private static final int MEMORY_RECALL_LIMIT = 6;
    private static final int FINDING_ANSWER_LIMIT = 6;

    private final ReviewSessionRepository sessionRepository;
    private final ReviewSessionMessageRepository messageRepository;
    private final FindingRepository findingRepository;
    private final ReviewMemoryService memoryService;
    private final ConversationContextAssembler contextAssembler;

    @Transactional
    public ReviewSessionDTO createSession(Long userId, ReviewSessionCreateRequestDTO request) {
        validateUserId(userId);
        if (request == null || isBlank(request.getProjectKey())) {
            throw new IllegalArgumentException("项目标识不能为空");
        }
        String title = !isBlank(request.getTitle()) ? request.getTitle().trim() : "项目审查会话";
        ReviewSession session = ReviewSession.builder()
                .userId(userId)
                .projectKey(request.getProjectKey().trim())
                .title(title)
                .summary("")
                .status(0)
                .build();
        return toSessionDTO(sessionRepository.save(session));
    }

    @Transactional(readOnly = true)
    public Page<ReviewSessionDTO> listSessions(Long userId, String projectKey, Pageable pageable) {
        validateUserId(userId);
        Pageable safePageable = pageable != null ? pageable : PageRequest.of(0, 20);
        Page<ReviewSession> sessions = isBlank(projectKey)
                ? sessionRepository.findByUserId(userId, safePageable)
                : sessionRepository.findByUserIdAndProjectKey(userId, projectKey, safePageable);
        return sessions.map(this::toSessionDTO);
    }

    @Transactional
    public ReviewSessionChatResponseDTO chat(Long userId, Long sessionId, ReviewSessionChatRequestDTO request) {
        validateUserId(userId);
        if (sessionId == null) {
            throw new IllegalArgumentException("会话ID不能为空");
        }
        if (request == null || isBlank(request.getContent())) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
        ReviewSession session = sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new RuntimeException("会话不存在: " + sessionId));

        ReviewSessionMessage userMessage = messageRepository.save(ReviewSessionMessage.builder()
                .sessionId(sessionId)
                .role("USER")
                .content(request.getContent().trim())
                .taskId(request.getTaskId())
                .findingId(request.getFindingId())
                .build());

        List<ReviewSessionMessage> recentMessages = messageRepository
                .findRecentBySessionId(sessionId, PageRequest.of(0, RECENT_MESSAGE_WINDOW))
                .stream()
                .sorted(Comparator.comparing(ReviewSessionMessage::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        List<ReviewSessionMemory> memories = memoryService.recall(
                userId,
                session.getProjectKey(),
                request.getContent(),
                MEMORY_RECALL_LIMIT
        );
        ConversationContext context = contextAssembler.assemble(
                session,
                request.getContent(),
                recentMessages,
                memories,
                taskEvidenceContext(request)
        );

        ReviewSessionMessage assistantMessage = messageRepository.save(ReviewSessionMessage.builder()
                .sessionId(sessionId)
                .role("ASSISTANT")
                .content(buildAssistantResponse(request, context))
                .taskId(request.getTaskId())
                .findingId(request.getFindingId())
                .build());

        return ReviewSessionChatResponseDTO.builder()
                .sessionId(sessionId)
                .userMessage(toMessageDTO(userMessage))
                .assistantMessage(toMessageDTO(assistantMessage))
                .memoryIds(context.getMemoryIds())
                .contextFromCache(context.isFromCache())
                .build();
    }

    @Transactional(readOnly = true)
    public Page<ReviewSessionMessageDTO> messages(Long userId, Long sessionId, Pageable pageable) {
        requireSession(userId, sessionId);
        Pageable safePageable = pageable != null ? pageable : PageRequest.of(0, 50);
        return messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId, safePageable)
                .map(this::toMessageDTO);
    }

    @Transactional
    public ReviewMemoryDTO addUserMemory(Long userId, Long sessionId, ReviewMemoryCreateRequestDTO request) {
        ReviewSession session = requireSession(userId, sessionId);
        if (request == null || isBlank(request.getContent())) {
            throw new IllegalArgumentException("记忆内容不能为空");
        }
        ReviewSessionMemory memory = memoryService.createActiveMemory(
                userId,
                session.getProjectKey(),
                sessionId,
                request.getMemoryType(),
                request.getContent(),
                request.getSourceId()
        );
        return toMemoryDTO(memory);
    }

    private ReviewSession requireSession(Long userId, Long sessionId) {
        validateUserId(userId);
        if (sessionId == null) {
            throw new IllegalArgumentException("会话ID不能为空");
        }
        return sessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new RuntimeException("会话不存在: " + sessionId));
    }

    private String taskEvidenceContext(ReviewSessionChatRequestDTO request) {
        if (request == null || (request.getTaskId() == null && request.getFindingId() == null)) {
            return "";
        }
        StringBuilder builder = new StringBuilder("Referenced review artifacts:");
        if (request.getTaskId() != null) {
            builder.append("\n- taskId=").append(request.getTaskId());
        }
        if (request.getFindingId() != null) {
            builder.append("\n- findingId=").append(request.getFindingId());
        }
        return builder.toString();
    }

    private String buildAssistantResponse(ReviewSessionChatRequestDTO request, ConversationContext context) {
        List<Finding> findings = loadReferencedFindings(request);
        if (!findings.isEmpty()) {
            return buildFindingAnswer(request, findings, context);
        }
        return buildNoFindingAnswer(request, context);
    }

    private List<Finding> loadReferencedFindings(ReviewSessionChatRequestDTO request) {
        if (request == null) {
            return List.of();
        }
        if (request.getFindingId() != null) {
            Optional<Finding> selected = findingRepository.findById(request.getFindingId());
            if (selected != null && selected.isPresent()) {
                Finding finding = selected.get();
                if (request.getTaskId() == null || request.getTaskId().equals(finding.getTaskId())) {
                    return List.of(finding);
                }
            }
        }
        if (request.getTaskId() == null) {
            return List.of();
        }
        List<Finding> findings = findingRepository.findByTaskId(request.getTaskId());
        if (findings == null) {
            return List.of();
        }
        return findings.stream()
                .sorted(Comparator
                        .comparing((Finding finding) -> finding.getSeverity() == null
                                ? Integer.MAX_VALUE
                                : finding.getSeverity())
                        .thenComparing(finding -> finding.getId() == null ? Long.MAX_VALUE : finding.getId()))
                .toList();
    }

    private String buildFindingAnswer(ReviewSessionChatRequestDTO request,
                                      List<Finding> findings,
                                      ConversationContext context) {
        StringBuilder builder = new StringBuilder("回答：\n");
        if (request != null && request.getFindingId() != null && findings.size() == 1) {
            builder.append("当前选中的审查问题如下：");
        } else {
            builder.append("这次审查发现 ").append(findings.size()).append(" 个问题。");
        }

        List<Finding> visibleFindings = findings.stream()
                .limit(FINDING_ANSWER_LIMIT)
                .toList();
        for (int i = 0; i < visibleFindings.size(); i++) {
            Finding finding = visibleFindings.get(i);
            builder.append("\n").append(i + 1)
                    .append(". [").append(severityLabel(finding)).append("] ")
                    .append(defaultIfBlank(finding.getTitle(), "未命名问题"));
            if (!isBlank(finding.getLocation())) {
                builder.append(" - ").append(finding.getLocation().trim());
            }
            if (!isBlank(finding.getDescription())) {
                builder.append("\n   描述：").append(compact(finding.getDescription(), 180));
            }
            if (!isBlank(finding.getSuggestion())) {
                builder.append("\n   建议：").append(compact(finding.getSuggestion(), 180));
            }
            if (!isBlank(finding.getGroundingSummary())) {
                builder.append("\n   依据摘要：").append(compact(finding.getGroundingSummary(), 180));
            } else if (finding.getEvidenceCount() != null && finding.getEvidenceCount() > 0) {
                builder.append("\n   依据摘要：已关联 ")
                        .append(finding.getEvidenceCount())
                        .append(" 条审查证据。");
            }
        }
        if (findings.size() > visibleFindings.size()) {
            builder.append("\n还有 ")
                    .append(findings.size() - visibleFindings.size())
                    .append(" 个问题未展开，可以继续追问高危项或指定某个问题。");
        }

        appendEvidenceSummary(builder, request, context);
        return builder.toString();
    }

    private String buildNoFindingAnswer(ReviewSessionChatRequestDTO request, ConversationContext context) {
        StringBuilder builder = new StringBuilder("回答：\n");
        if (request != null && request.getTaskId() != null) {
            builder.append("当前会话关联 Task #")
                    .append(request.getTaskId())
                    .append("，但暂时没有读取到这个任务的审查问题。请确认审查任务已完成，或在结果区选择具体问题后再追问。");
        } else if (request != null && request.getFindingId() != null) {
            builder.append("当前会话关联 Finding #")
                    .append(request.getFindingId())
                    .append("，但没有读取到对应的问题详情。请从问题列表重新选择后再追问。");
        } else {
            builder.append("我已记录你的问题。当前消息没有关联具体 task 或 finding，因此只能基于项目会话上下文继续跟进。");
        }
        appendEvidenceSummary(builder, request, context);
        return builder.toString();
    }

    private void appendEvidenceSummary(StringBuilder builder,
                                       ReviewSessionChatRequestDTO request,
                                       ConversationContext context) {
        builder.append("\n\n依据：");
        if (request != null && request.getTaskId() != null) {
            builder.append("\n- 当前关联 Task #").append(request.getTaskId());
        }
        if (request != null && request.getFindingId() != null) {
            builder.append("\n- 当前关联 Finding #").append(request.getFindingId());
        }
        List<Long> memoryIds = context != null ? context.getMemoryIds() : List.of();
        if (memoryIds != null && !memoryIds.isEmpty()) {
            builder.append("\n- 已召回项目记忆：").append(memoryIds);
        }
        builder.append("\n- 已使用会话上下文和项目记忆生成回答，但不会直接展示内部 prompt。");
    }

    private String severityLabel(Finding finding) {
        SeverityEnum severity = SeverityEnum.fromValue(finding != null ? finding.getSeverity() : null);
        return switch (severity) {
            case CRITICAL -> "严重";
            case HIGH -> "高危";
            case MEDIUM -> "中危";
            case LOW -> "低危";
        };
    }

    private String compact(String value, int maxLength) {
        if (isBlank(value)) {
            return "";
        }
        String compacted = value.replaceAll("\\s+", " ").trim();
        if (compacted.length() <= maxLength) {
            return compacted;
        }
        return compacted.substring(0, maxLength) + "...";
    }

    private String defaultIfBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private ReviewSessionDTO toSessionDTO(ReviewSession session) {
        if (session == null) {
            return null;
        }
        return ReviewSessionDTO.builder()
                .id(session.getId())
                .userId(session.getUserId())
                .projectKey(session.getProjectKey())
                .title(session.getTitle())
                .summary(session.getSummary())
                .status(session.getStatus())
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .build();
    }

    private ReviewSessionMessageDTO toMessageDTO(ReviewSessionMessage message) {
        if (message == null) {
            return null;
        }
        return ReviewSessionMessageDTO.builder()
                .id(message.getId())
                .sessionId(message.getSessionId())
                .role(message.getRole())
                .content(message.getContent())
                .taskId(message.getTaskId())
                .findingId(message.getFindingId())
                .createdAt(message.getCreatedAt())
                .build();
    }

    private ReviewMemoryDTO toMemoryDTO(ReviewSessionMemory memory) {
        if (memory == null) {
            return null;
        }
        return ReviewMemoryDTO.builder()
                .id(memory.getId())
                .userId(memory.getUserId())
                .projectKey(memory.getProjectKey())
                .sessionId(memory.getSessionId())
                .scope(memory.getScope())
                .memoryType(memory.getMemoryType())
                .content(memory.getContent())
                .summary(memory.getSummary())
                .sourceType(memory.getSourceType())
                .sourceId(memory.getSourceId())
                .confidence(memory.getConfidence())
                .status(memory.getStatus())
                .createdAt(memory.getCreatedAt())
                .lastUsedAt(memory.getLastUsedAt())
                .build();
    }

    private void validateUserId(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
