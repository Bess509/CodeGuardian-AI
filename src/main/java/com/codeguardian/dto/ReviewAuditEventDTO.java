package com.codeguardian.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewAuditEventDTO {
    private Long id;
    private Long taskId;
    private String eventType;
    private String stage;
    private String actor;
    private String message;
    private String payloadHash;
    private String previousHash;
    private String eventHash;
    private String signatureKeyId;
    private String signatureAlgorithm;
    private String eventSignature;
    private Map<String, Object> metadata;
    private LocalDateTime createdAt;
}
