package com.codeguardian.service.provenance;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Service
@RequiredArgsConstructor
public class ProvenanceHashService {

    private final ObjectMapper objectMapper;

    public String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((value != null ? value : "").getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate SHA-256", e);
        }
    }

    public String hashPayload(Object payload) {
        return sha256Hex(canonicalJson(payload));
    }

    public String hmacSha256Hex(String value, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec((secret != null ? secret : "").getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] bytes = mac.doFinal((value != null ? value : "").getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA256", e);
        }
    }

    public boolean secureEquals(String left, String right) {
        if (left == null || right == null) {
            return left == right;
        }
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    public String hashAuditEvent(Long taskId,
                                 String eventType,
                                 String stage,
                                 String actor,
                                 String message,
                                 String payloadHash,
                                 String previousHash,
                                 LocalDateTime createdAt) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("taskId", taskId);
        canonical.put("eventType", eventType);
        canonical.put("stage", stage);
        canonical.put("actor", actor);
        canonical.put("message", message);
        canonical.put("payloadHash", payloadHash);
        canonical.put("previousHash", previousHash);
        canonical.put("createdAt", createdAt != null ? createdAt.truncatedTo(ChronoUnit.MICROS).toString() : null);
        return hashPayload(canonical);
    }

    public String canonicalJson(Object value) {
        try {
            return objectMapper.writeValueAsString(sortRecursively(value));
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    @SuppressWarnings("unchecked")
    private Object sortRecursively(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sorted.put(String.valueOf(entry.getKey()), sortRecursively(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> values = new ArrayList<>();
            for (Object item : iterable) {
                values.add(sortRecursively(item));
            }
            values.sort(Comparator.comparing(String::valueOf));
            return values;
        }
        return value;
    }
}
