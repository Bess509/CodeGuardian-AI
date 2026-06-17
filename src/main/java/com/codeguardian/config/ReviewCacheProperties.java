package com.codeguardian.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.review-cache")
@Data
public class ReviewCacheProperties {

    private boolean enabled = true;

    private int ttlDays = 14;

    private String namespaceVersion = "v1";

    private String promptVersion = "default";

    private SimHash simhash = new SimHash();

    @Data
    public static class SimHash {
        private int maxHammingDistance = 3;
        private int maxCandidates = 50;
        private int segments = 4;
    }
}
