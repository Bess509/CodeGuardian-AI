package com.codeguardian.service.rag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievedKnowledgeChunk {
    private String chunkId;
    private String sourceDocumentId;
    private String title;
    private String content;
    private String sourceRef;
    private String retrievalMode;
    private Integer rank;
    private Double score;
    private Map<String, Object> metadata;

    public String toPromptSnippet() {
        String safeContent = content != null ? content : "";
        if (title == null || title.isBlank()) {
            return safeContent;
        }
        return "【" + title + "】\n" + safeContent;
    }
}
