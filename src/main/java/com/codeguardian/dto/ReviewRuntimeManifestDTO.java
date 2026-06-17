package com.codeguardian.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewRuntimeManifestDTO {
    private String manifestVersion;
    private String manifestHashAlgorithm;
    private String manifestHash;
    private EnvironmentSnapshot environment;
    private AiSnapshot ai;
    private RagSnapshot rag;
    private AuditSnapshot audit;
    private DatabaseGuardSnapshot databaseGuards;
    private GroundingPolicySnapshot groundingPolicy;
    private SettingsSnapshot settings;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EnvironmentSnapshot {
        private String applicationName;
        private List<String> activeProfiles;
        private String javaVersion;
        private String osName;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AiSnapshot {
        private Boolean enabled;
        private String provider;
        private Integer timeoutSeconds;
        private Integer maxRetries;
        private Map<String, ProviderSnapshot> providers;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProviderSnapshot {
        private Boolean enabled;
        private Boolean configured;
        private Boolean apiKeyConfigured;
        private String baseUrlHash;
        private String baseUrlHost;
        private String model;
        private Integer connectTimeoutSeconds;
        private Integer readTimeoutSeconds;
        private Integer writeTimeoutSeconds;
        private Integer maxRetries;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RagSnapshot {
        private Boolean vectorizeOnStartup;
        private Boolean openAiEmbeddingEnabled;
        private Boolean transformerEmbeddingEnabled;
        private Boolean ollamaEmbeddingEnabled;
        private String retrievalStrategy;
        private Integer reviewRetrievalTopK;
        private Integer queryMaxChars;
        private Integer fallbackSnippetMaxChars;
        private String vectorIndexType;
        private String vectorDistanceType;
        private Integer vectorDimensions;
        private Double bm25K1;
        private Double bm25B;
        private String knowledgeBaseFingerprint;
        private Integer knowledgeBaseDocumentCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuditSnapshot {
        private Boolean signingEnabled;
        private Boolean signingActive;
        private Boolean signingSecretConfigured;
        private String signingKeyId;
        private String signingAlgorithm;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DatabaseGuardSnapshot {
        private String guardVersion;
        private Boolean querySupported;
        private Boolean appendOnlyGuardsInstalled;
        private String guardFunctionName;
        private List<String> protectedTables;
        private Map<String, String> expectedTriggers;
        private Integer expectedTriggerCount;
        private Integer installedTriggerCount;
        private Boolean updatesBlocked;
        private Boolean deletesBlocked;
        private String verificationReason;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GroundingPolicySnapshot {
        private String policyVersion;
        private String minSeverity;
        private List<String> requiredEvidenceTypes;
        private Boolean requireSourceAnchors;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SettingsSnapshot {
        private String projectRootHash;
        private Boolean projectRootConfigured;
        private String ruleStandard;
        private String rulePreset;
        private String includePaths;
        private String excludePaths;
        private Integer maxIssues;
        private Map<String, Boolean> ruleCategories;
        private Map<String, Integer> ruleWeights;
    }
}
