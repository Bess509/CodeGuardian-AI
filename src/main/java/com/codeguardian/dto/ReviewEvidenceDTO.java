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
public class ReviewEvidenceDTO {
    private Long id;
    private Long taskId;
    private Long findingId;
    private String evidenceType;
    private String sourceName;
    private String sourceRef;
    private String locator;
    private Integer startLine;
    private Integer endLine;
    private String excerpt;
    private String contentHash;
    private Double relevanceScore;
    private Map<String, Object> metadata;
    private LocalDateTime createdAt;
}
