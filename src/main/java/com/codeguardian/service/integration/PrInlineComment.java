package com.codeguardian.service.integration;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PrInlineComment {
    private Long findingId;
    private String path;
    private Integer line;
    private String side;
    private String severity;
    private String title;
    private String body;
    private Boolean publishable;
    private String reason;
}
