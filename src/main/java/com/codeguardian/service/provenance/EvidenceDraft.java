package com.codeguardian.service.provenance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Thread-local evidence captured before the task/finding id is known.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceDraft {

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
}
