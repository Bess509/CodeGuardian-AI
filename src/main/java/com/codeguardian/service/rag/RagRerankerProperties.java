package com.codeguardian.service.rag;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.rag.reranker")
public class RagRerankerProperties {

    private boolean enabled = true;
    private String endpoint = "http://localhost:8081/rerank";
    private String modelId = "BAAI/bge-reranker-base";
    private int candidateTopK = 32;
    private int timeoutMillis = 1500;
    private int maxQueryChars = 2200;
    private int maxTextChars = 2400;
    private boolean rawScores = false;
    private double crossEncoderWeight = 0.85d;
    private double fusionWeight = 0.15d;
}
