package com.codeguardian.service.session;

import com.codeguardian.entity.ReviewSession;
import com.codeguardian.entity.ReviewSessionMemory;
import com.codeguardian.entity.ReviewSessionMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Component
public class ConversationContextAssembler {

    private static final int DEFAULT_SLIDING_WINDOW_SIZE = 20;
    private static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(20);

    private final StringRedisTemplate redisTemplate;
    private final int slidingWindowSize;
    private final Duration cacheTtl;

    @Autowired
    public ConversationContextAssembler(@Autowired(required = false) StringRedisTemplate redisTemplate) {
        this(redisTemplate, DEFAULT_SLIDING_WINDOW_SIZE, DEFAULT_CACHE_TTL);
    }

    public ConversationContextAssembler(StringRedisTemplate redisTemplate, int slidingWindowSize, Duration cacheTtl) {
        this.redisTemplate = redisTemplate;
        this.slidingWindowSize = Math.max(1, slidingWindowSize);
        this.cacheTtl = cacheTtl != null ? cacheTtl : DEFAULT_CACHE_TTL;
    }

    public ConversationContext assemble(ReviewSession session,
                                        String currentQuestion,
                                        List<ReviewSessionMessage> messages,
                                        List<ReviewSessionMemory> memories,
                                        String taskEvidenceContext) {
        String cacheKey = cacheKey(session, currentQuestion, messages, memories, taskEvidenceContext);
        String cached = readCache(cacheKey);
        List<Long> memoryIds = memoryIds(memories);
        if (cached != null && !cached.isBlank()) {
            return ConversationContext.builder()
                    .promptContext(cached)
                    .memoryIds(memoryIds)
                    .fromCache(true)
                    .build();
        }

        String promptContext = buildPromptContext(session, currentQuestion, messages, memories, taskEvidenceContext);
        writeCache(cacheKey, promptContext);
        return ConversationContext.builder()
                .promptContext(promptContext)
                .memoryIds(memoryIds)
                .fromCache(false)
                .build();
    }

    private String buildPromptContext(ReviewSession session,
                                      String currentQuestion,
                                      List<ReviewSessionMessage> messages,
                                      List<ReviewSessionMemory> memories,
                                      String taskEvidenceContext) {
        StringBuilder context = new StringBuilder();
        appendSection(context, "Current user question", currentQuestion);
        appendSection(context, "Task evidence context", taskEvidenceContext);
        appendSection(context, "Recent conversation", recentConversation(messages));
        if (session != null) {
            appendSection(context, "Session summary", session.getSummary());
            appendSection(context, "Project key", session.getProjectKey());
        }
        appendSection(context, "Long-term memories", memoryContext(memories));
        return context.toString().trim();
    }

    private String recentConversation(List<ReviewSessionMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        List<ReviewSessionMessage> sorted = messages.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(
                        ReviewSessionMessage::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())
                ))
                .toList();
        int fromIndex = Math.max(0, sorted.size() - slidingWindowSize);
        StringBuilder builder = new StringBuilder();
        for (ReviewSessionMessage message : sorted.subList(fromIndex, sorted.size())) {
            if (message.getContent() == null || message.getContent().isBlank()) {
                continue;
            }
            builder.append(safe(message.getRole()))
                    .append(": ")
                    .append(message.getContent().trim())
                    .append('\n');
        }
        return builder.toString().trim();
    }

    private String memoryContext(List<ReviewSessionMemory> memories) {
        if (memories == null || memories.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (ReviewSessionMemory memory : memories) {
            if (memory == null) {
                continue;
            }
            String text = firstNonBlank(memory.getSummary(), memory.getContent());
            if (text == null || text.isBlank()) {
                continue;
            }
            builder.append("- [")
                    .append(safe(memory.getScope()))
                    .append("] ")
                    .append(text.trim())
                    .append(" (confidence=")
                    .append(memory.getConfidence() != null ? memory.getConfidence() : 0.0d)
                    .append(")\n");
        }
        return builder.toString().trim();
    }

    private List<Long> memoryIds(List<ReviewSessionMemory> memories) {
        if (memories == null) {
            return List.of();
        }
        return memories.stream()
                .filter(Objects::nonNull)
                .map(ReviewSessionMemory::getId)
                .filter(Objects::nonNull)
                .toList();
    }

    private void appendSection(StringBuilder builder, String title, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append("\n\n");
        }
        builder.append("## ").append(title).append('\n').append(content.trim());
    }

    private String readCache(String cacheKey) {
        if (redisTemplate == null || cacheKey == null) {
            return null;
        }
        try {
            return redisTemplate.opsForValue().get(cacheKey);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void writeCache(String cacheKey, String promptContext) {
        if (redisTemplate == null || cacheKey == null || promptContext == null || promptContext.isBlank()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(cacheKey, promptContext, cacheTtl);
        } catch (Exception ignored) {
            // Redis is an acceleration layer only; persisted messages remain the source of truth.
        }
    }

    private String cacheKey(ReviewSession session,
                            String currentQuestion,
                            List<ReviewSessionMessage> messages,
                            List<ReviewSessionMemory> memories,
                            String taskEvidenceContext) {
        if (session == null || session.getId() == null) {
            return null;
        }
        int fingerprint = Objects.hash(
                currentQuestion,
                taskEvidenceContext,
                messages != null ? messages.size() : 0,
                memoryIds(memories)
        );
        return "session:%d:prompt-context:%s".formatted(session.getId(), Integer.toHexString(fingerprint));
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    private String safe(String value) {
        return value != null ? value : "";
    }
}
