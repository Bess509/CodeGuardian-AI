package com.codeguardian.service.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class CrossEncoderRerankerClient implements RerankerClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final RagRerankerProperties properties;
    private final ObjectMapper objectMapper;

    @Override
    public boolean isEnabled() {
        return properties != null && properties.isEnabled()
                && properties.getEndpoint() != null
                && !properties.getEndpoint().isBlank();
    }

    @Override
    public RerankResponse rerank(String query, List<RetrievedKnowledgeChunk> chunks) {
        if (!isEnabled() || chunks == null || chunks.isEmpty()) {
            return new RerankResponse(modelId(), endpoint(), List.of());
        }
        try {
            List<String> texts = chunks.stream()
                    .map(this::toRerankText)
                    .toList();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("query", trim(query, properties.getMaxQueryChars()));
            payload.put("texts", texts);
            payload.put("raw_scores", properties.isRawScores());
            payload.put("return_text", false);

            Request request = new Request.Builder()
                    .url(endpoint())
                    .post(RequestBody.create(objectMapper.writeValueAsString(payload), JSON))
                    .build();

            try (Response response = client().newCall(request).execute()) {
                String body = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.warn("Cross-encoder reranker returned HTTP {}: {}",
                            response.code(), trim(body, 300));
                    return new RerankResponse(modelId(), endpoint(), List.of());
                }
                return new RerankResponse(modelId(), endpoint(), parseResults(body, chunks));
            }
        } catch (Exception e) {
            log.warn("Cross-encoder reranker failed, falling back to fusion score: {}", e.getMessage());
            return new RerankResponse(modelId(), endpoint(), List.of());
        }
    }

    private OkHttpClient client() {
        long timeoutMillis = Math.max(100, properties.getTimeoutMillis());
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofMillis(timeoutMillis))
                .readTimeout(Duration.ofMillis(timeoutMillis))
                .writeTimeout(Duration.ofMillis(timeoutMillis))
                .callTimeout(timeoutMillis * 2, TimeUnit.MILLISECONDS)
                .build();
    }

    private List<RerankResult> parseResults(String body, List<RetrievedKnowledgeChunk> chunks) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode items = root.has("results") ? root.get("results") : root;
        if (items == null || !items.isArray()) {
            return List.of();
        }
        List<RerankResult> results = new ArrayList<>();
        for (JsonNode item : items) {
            int index = intValue(item, "index", intValue(item.path("document"), "index", -1));
            if (index < 0 || index >= chunks.size()) {
                continue;
            }
            Double score = scoreValue(item);
            if (score == null) {
                continue;
            }
            results.add(new RerankResult(index, chunks.get(index).getChunkId(), score));
        }
        return results;
    }

    private Double scoreValue(JsonNode item) {
        for (String key : List.of("score", "relevance_score")) {
            JsonNode value = item.get(key);
            if (value != null && value.isNumber()) {
                return value.asDouble();
            }
        }
        return null;
    }

    private int intValue(JsonNode item, String key, int fallback) {
        JsonNode value = item != null ? item.get(key) : null;
        return value != null && value.canConvertToInt() ? value.asInt() : fallback;
    }

    private String toRerankText(RetrievedKnowledgeChunk chunk) {
        Map<String, Object> metadata = chunk.getMetadata() != null ? chunk.getMetadata() : Map.of();
        StringBuilder text = new StringBuilder();
        append(text, "Title", firstNonBlank(chunk.getTitle(), stringValue(metadata.get("title"))));
        append(text, "Category", stringValue(metadata.get("category")));
        append(text, "Heading", stringValue(metadata.get("heading_path")));
        append(text, "Rule IDs", stringValue(metadata.get("rule_ids")));
        text.append("Content:\n")
                .append(RagTextSanitizer.clean(chunk.getContent()));
        return trim(text.toString(), properties.getMaxTextChars());
    }

    private void append(StringBuilder target, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        target.append(label).append(": ").append(value.trim()).append('\n');
    }

    private String modelId() {
        return properties != null ? properties.getModelId() : "";
    }

    private String endpoint() {
        return properties != null ? properties.getEndpoint() : "";
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    private String stringValue(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    private String trim(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        int limit = Math.max(1, maxLength);
        if (value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit) + "\n... [truncated]";
    }
}
