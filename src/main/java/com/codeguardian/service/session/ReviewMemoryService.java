package com.codeguardian.service.session;

import com.codeguardian.entity.ReviewSessionMemory;
import com.codeguardian.repository.ReviewSessionMemoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ReviewMemoryService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_CANDIDATE = "CANDIDATE";
    private static final String STATUS_ARCHIVED = "ARCHIVED";
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{IsHan}]|[a-zA-Z0-9_]+");
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "and", "or", "to", "of", "in", "on", "for", "with",
            "is", "are", "be", "as", "by", "from", "this", "that"
    );

    private final ReviewSessionMemoryRepository memoryRepository;

    @Transactional(readOnly = true)
    public List<ReviewSessionMemory> recall(Long userId, String projectKey, String query, int limit) {
        if (userId == null || isBlank(projectKey) || isBlank(query) || limit <= 0) {
            return List.of();
        }
        Set<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return List.of();
        }
        return memoryRepository.findRecallCandidates(userId, projectKey, STATUS_ACTIVE).stream()
                .filter(memory -> !tokenize(memoryText(memory)).isEmpty())
                .sorted(Comparator.comparingDouble((ReviewSessionMemory memory) ->
                        score(memory, queryTokens)).reversed())
                .limit(limit)
                .toList();
    }

    @Transactional
    public ReviewSessionMemory createActiveMemory(Long userId,
                                                  String projectKey,
                                                  Long sessionId,
                                                  String memoryType,
                                                  String content,
                                                  String sourceId) {
        return saveMemory(userId, projectKey, sessionId, "PROJECT_MEMORY", memoryType, content,
                "USER_INPUT", sourceId, 1.0d, STATUS_ACTIVE);
    }

    @Transactional
    public ReviewSessionMemory createCandidateMemory(Long userId,
                                                     String projectKey,
                                                     Long sessionId,
                                                     String memoryType,
                                                     String content,
                                                     String sourceType,
                                                     String sourceId,
                                                     Double confidence) {
        return saveMemory(userId, projectKey, sessionId, "PROJECT_MEMORY", memoryType, content,
                sourceType, sourceId, confidence, STATUS_CANDIDATE);
    }

    @Transactional
    public ReviewSessionMemory archiveMemory(Long memoryId) {
        ReviewSessionMemory memory = memoryRepository.findById(memoryId)
                .orElseThrow(() -> new RuntimeException("长期记忆不存在: " + memoryId));
        memory.setStatus(STATUS_ARCHIVED);
        return memoryRepository.save(memory);
    }

    private ReviewSessionMemory saveMemory(Long userId,
                                           String projectKey,
                                           Long sessionId,
                                           String scope,
                                           String memoryType,
                                           String content,
                                           String sourceType,
                                           String sourceId,
                                           Double confidence,
                                           String status) {
        if (userId == null) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        if (isBlank(projectKey)) {
            throw new IllegalArgumentException("项目标识不能为空");
        }
        if (isBlank(content)) {
            throw new IllegalArgumentException("记忆内容不能为空");
        }
        String normalizedContent = content.trim();
        ReviewSessionMemory memory = ReviewSessionMemory.builder()
                .userId(userId)
                .projectKey(projectKey.trim())
                .sessionId(sessionId)
                .scope(isBlank(scope) ? "PROJECT_MEMORY" : scope.trim())
                .memoryType(isBlank(memoryType) ? "PROJECT_CONTEXT" : memoryType.trim())
                .content(normalizedContent)
                .summary(normalizedContent)
                .status(status)
                .sourceType(isBlank(sourceType) ? "USER_INPUT" : sourceType.trim())
                .sourceId(sourceId)
                .confidence(confidence != null ? confidence : 0.5d)
                .embeddingText(normalizedContent)
                .build();
        return memoryRepository.save(memory);
    }

    private double score(ReviewSessionMemory memory, Set<String> queryTokens) {
        Set<String> memoryTokens = tokenize(memoryText(memory));
        double overlap = memoryTokens.stream()
                .filter(queryTokens::contains)
                .count();
        double similarity = overlap / Math.max(1, Math.min(queryTokens.size(), memoryTokens.size()));
        double confidence = memory.getConfidence() != null ? memory.getConfidence() : 0.5d;
        double recency = recencyScore(memory);
        return similarity * 0.70d + confidence * 0.20d + recency * 0.10d;
    }

    private double recencyScore(ReviewSessionMemory memory) {
        LocalDateTime reference = memory.getLastUsedAt() != null
                ? memory.getLastUsedAt()
                : memory.getCreatedAt();
        if (reference == null) {
            return 0.0d;
        }
        long hours = Math.max(0, Duration.between(reference, LocalDateTime.now()).toHours());
        return 1.0d / (1.0d + Math.min(hours, 720) / 24.0d);
    }

    private Set<String> tokenize(String text) {
        if (isBlank(text)) {
            return Set.of();
        }
        Matcher matcher = TOKEN_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
        Set<String> tokens = new LinkedHashSet<>();
        while (matcher.find()) {
            String token = matcher.group();
            if (!token.isBlank() && !STOP_WORDS.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private String memoryText(ReviewSessionMemory memory) {
        if (memory == null) {
            return "";
        }
        if (!isBlank(memory.getEmbeddingText())) {
            return memory.getEmbeddingText();
        }
        if (!isBlank(memory.getSummary())) {
            return memory.getSummary();
        }
        return memory.getContent();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
