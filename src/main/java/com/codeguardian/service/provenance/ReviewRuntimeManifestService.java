package com.codeguardian.service.provenance;

import com.codeguardian.config.AIConfigProperties;
import com.codeguardian.config.AppendOnlySchemaGuardInitializer;
import com.codeguardian.config.AuditSigningProperties;
import com.codeguardian.dto.ReviewRuntimeManifestDTO;
import com.codeguardian.model.dto.SettingsDTO;
import com.codeguardian.service.SystemConfigService;
import com.codeguardian.service.rag.KnowledgeBaseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class ReviewRuntimeManifestService {

    public static final String MANIFEST_VERSION = "codeguardian-review-runtime-manifest-v1";

    private final AIConfigProperties aiConfigProperties;
    private final AuditSigningProperties auditSigningProperties;
    private final SystemConfigService systemConfigService;
    private final Environment environment;
    private final ProvenanceHashService hashService;

    @Autowired(required = false)
    @Lazy
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;

    @Autowired
    public ReviewRuntimeManifestService(AIConfigProperties aiConfigProperties,
                                        AuditSigningProperties auditSigningProperties,
                                        SystemConfigService systemConfigService,
                                        Environment environment,
                                        ProvenanceHashService hashService) {
        this.aiConfigProperties = aiConfigProperties;
        this.auditSigningProperties = auditSigningProperties;
        this.systemConfigService = systemConfigService;
        this.environment = environment;
        this.hashService = hashService;
    }

    public ReviewRuntimeManifestService(ProvenanceHashService hashService) {
        this(null, null, null, null, hashService);
    }

    public ReviewRuntimeManifestDTO buildManifest() {
        ReviewRuntimeManifestDTO manifest = ReviewRuntimeManifestDTO.builder()
                .manifestVersion(MANIFEST_VERSION)
                .manifestHashAlgorithm("SHA-256")
                .environment(buildEnvironmentSnapshot())
                .ai(buildAiSnapshot())
                .rag(buildRagSnapshot())
                .audit(buildAuditSnapshot())
                .databaseGuards(buildDatabaseGuardSnapshot())
                .groundingPolicy(buildGroundingPolicySnapshot())
                .settings(buildSettingsSnapshot())
                .build();
        manifest.setManifestHash(calculateManifestHash(manifest));
        return manifest;
    }

    public boolean verifyManifestHash(ReviewRuntimeManifestDTO manifest) {
        if (manifest == null || manifest.getManifestHash() == null || manifest.getManifestHash().isBlank()) {
            return false;
        }
        return hashService.secureEquals(calculateManifestHash(manifest), manifest.getManifestHash());
    }

    public String calculateManifestHash(ReviewRuntimeManifestDTO manifest) {
        if (manifest == null) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("manifestVersion", manifest.getManifestVersion());
        payload.put("manifestHashAlgorithm", manifest.getManifestHashAlgorithm());
        payload.put("environment", manifest.getEnvironment());
        payload.put("ai", manifest.getAi());
        payload.put("rag", manifest.getRag());
        payload.put("audit", manifest.getAudit());
        payload.put("databaseGuards", manifest.getDatabaseGuards());
        payload.put("groundingPolicy", manifest.getGroundingPolicy());
        payload.put("settings", manifest.getSettings());
        return hashService.hashPayload(payload);
    }

    private ReviewRuntimeManifestDTO.EnvironmentSnapshot buildEnvironmentSnapshot() {
        String[] activeProfiles = environment != null ? environment.getActiveProfiles() : new String[0];
        return ReviewRuntimeManifestDTO.EnvironmentSnapshot.builder()
                .applicationName(env("spring.application.name", "code-review-ai-agent"))
                .activeProfiles(Arrays.stream(activeProfiles).sorted().toList())
                .javaVersion(System.getProperty("java.version"))
                .osName(System.getProperty("os.name"))
                .build();
    }

    private ReviewRuntimeManifestDTO.AiSnapshot buildAiSnapshot() {
        Map<String, ReviewRuntimeManifestDTO.ProviderSnapshot> providers = new TreeMap<>();
        if (aiConfigProperties != null && aiConfigProperties.getProviders() != null) {
            for (Map.Entry<String, AIConfigProperties.ProviderConfig> entry : aiConfigProperties.getProviders().entrySet()) {
                providers.put(entry.getKey(), toProviderSnapshot(entry.getValue()));
            }
        }
        return ReviewRuntimeManifestDTO.AiSnapshot.builder()
                .enabled(aiConfigProperties != null ? aiConfigProperties.getEnabled() : null)
                .provider(aiConfigProperties != null ? aiConfigProperties.getProvider() : null)
                .timeoutSeconds(aiConfigProperties != null ? aiConfigProperties.getTimeout() : null)
                .maxRetries(aiConfigProperties != null ? aiConfigProperties.getMaxRetries() : null)
                .providers(providers)
                .build();
    }

    private ReviewRuntimeManifestDTO.ProviderSnapshot toProviderSnapshot(AIConfigProperties.ProviderConfig config) {
        if (config == null) {
            return ReviewRuntimeManifestDTO.ProviderSnapshot.builder().configured(false).build();
        }
        boolean apiKeyConfigured = config.getApiKey() != null && !config.getApiKey().isBlank();
        boolean baseUrlConfigured = config.getBaseUrl() != null && !config.getBaseUrl().isBlank();
        return ReviewRuntimeManifestDTO.ProviderSnapshot.builder()
                .enabled(config.getEnabled())
                .configured(Boolean.TRUE.equals(config.getEnabled()) && apiKeyConfigured && baseUrlConfigured)
                .apiKeyConfigured(apiKeyConfigured)
                .baseUrlHash(baseUrlConfigured ? hashService.sha256Hex(config.getBaseUrl()) : null)
                .baseUrlHost(baseUrlConfigured ? hostOf(config.getBaseUrl()) : null)
                .model(config.getModel())
                .connectTimeoutSeconds(config.getConnectTimeout())
                .readTimeoutSeconds(config.getReadTimeout())
                .writeTimeoutSeconds(config.getWriteTimeout())
                .maxRetries(config.getMaxRetries())
                .build();
    }

    private ReviewRuntimeManifestDTO.RagSnapshot buildRagSnapshot() {
        return ReviewRuntimeManifestDTO.RagSnapshot.builder()
                .vectorizeOnStartup(envBoolean("app.rag.vectorize-on-startup", true))
                .openAiEmbeddingEnabled(envBoolean("spring.ai.openai.embedding.enabled", true))
                .transformerEmbeddingEnabled(envBoolean("spring.ai.embedding.transformer.enabled", true))
                .ollamaEmbeddingEnabled(envBoolean("app.rag.ollama.embedding.enabled", true))
                .retrievalStrategy("VECTOR_BM25_FUSED_TARGET_CONTEXT")
                .reviewRetrievalTopK(8)
                .queryMaxChars(2400)
                .fallbackSnippetMaxChars(800)
                .vectorIndexType(env("app.rag.vectorstore.pgvector.index-type", "HNSW"))
                .vectorDistanceType(env("app.rag.vectorstore.pgvector.distance-type", "COSINE_DISTANCE"))
                .vectorDimensions(envInteger("app.rag.vectorstore.pgvector.dimensions", 384))
                .bm25K1(1.5d)
                .bm25B(0.75d)
                .knowledgeBaseFingerprint(knowledgeBaseFingerprint())
                .knowledgeBaseDocumentCount(knowledgeBaseDocumentCount())
                .build();
    }

    private String knowledgeBaseFingerprint() {
        if (knowledgeBaseService == null) {
            return null;
        }
        try {
            return knowledgeBaseService.getCorpusFingerprint();
        } catch (Exception ignored) {
            return null;
        }
    }

    private Integer knowledgeBaseDocumentCount() {
        if (knowledgeBaseService == null) {
            return null;
        }
        try {
            return knowledgeBaseService.getLoadedDocumentCount();
        } catch (Exception ignored) {
            return null;
        }
    }

    private ReviewRuntimeManifestDTO.AuditSnapshot buildAuditSnapshot() {
        AuditSigningProperties signing = auditSigningProperties != null ? auditSigningProperties : new AuditSigningProperties();
        return ReviewRuntimeManifestDTO.AuditSnapshot.builder()
                .signingEnabled(signing.isEnabled())
                .signingActive(signing.isActive())
                .signingSecretConfigured(signing.getSecret() != null && !signing.getSecret().isBlank())
                .signingKeyId(signing.getKeyId())
                .signingAlgorithm(signing.getAlgorithm())
                .build();
    }

    private ReviewRuntimeManifestDTO.DatabaseGuardSnapshot buildDatabaseGuardSnapshot() {
        Map<String, String> expectedTriggers = expectedAppendOnlyTriggers();
        ReviewRuntimeManifestDTO.DatabaseGuardSnapshot.DatabaseGuardSnapshotBuilder builder =
                ReviewRuntimeManifestDTO.DatabaseGuardSnapshot.builder()
                        .guardVersion("codeguardian-db-append-only-guards-v1")
                        .guardFunctionName(AppendOnlySchemaGuardInitializer.GUARD_FUNCTION_NAME)
                        .protectedTables(expectedTriggers.keySet().stream().sorted().toList())
                        .expectedTriggers(expectedTriggers)
                        .expectedTriggerCount(expectedTriggers.size())
                        .updatesBlocked(true)
                        .deletesBlocked(true);

        if (jdbcTemplate == null) {
            return builder
                    .querySupported(false)
                    .appendOnlyGuardsInstalled(null)
                    .installedTriggerCount(null)
                    .verificationReason("jdbc_template_unavailable")
                    .build();
        }

        try {
            Integer functionCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM pg_proc WHERE proname = ?",
                    Integer.class,
                    AppendOnlySchemaGuardInitializer.GUARD_FUNCTION_NAME
            );
            Integer triggerCount = jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM pg_trigger t
                    JOIN pg_class c ON c.oid = t.tgrelid
                    WHERE NOT t.tgisinternal
                      AND (
                        (c.relname = ? AND t.tgname = ?)
                        OR (c.relname = ? AND t.tgname = ?)
                      )
                      AND pg_get_triggerdef(t.oid) ILIKE '%BEFORE%'
                      AND pg_get_triggerdef(t.oid) ILIKE '%UPDATE%'
                      AND pg_get_triggerdef(t.oid) ILIKE '%DELETE%'
                      AND pg_get_triggerdef(t.oid) ILIKE ?
                    """,
                    Integer.class,
                    AppendOnlySchemaGuardInitializer.REVIEW_EVIDENCE_TABLE,
                    AppendOnlySchemaGuardInitializer.REVIEW_EVIDENCE_TRIGGER,
                    AppendOnlySchemaGuardInitializer.REVIEW_AUDIT_EVENTS_TABLE,
                    AppendOnlySchemaGuardInitializer.REVIEW_AUDIT_EVENTS_TRIGGER,
                    "%" + AppendOnlySchemaGuardInitializer.GUARD_FUNCTION_NAME + "%"
            );
            boolean installed = functionCount != null && functionCount > 0
                    && triggerCount != null && triggerCount == expectedTriggers.size();
            return builder
                    .querySupported(true)
                    .appendOnlyGuardsInstalled(installed)
                    .installedTriggerCount(triggerCount)
                    .verificationReason(installed ? "ok" : "append_only_guard_missing")
                    .build();
        } catch (Exception e) {
            return builder
                    .querySupported(false)
                    .appendOnlyGuardsInstalled(null)
                    .installedTriggerCount(null)
                    .verificationReason("guard_query_failed")
                    .build();
        }
    }

    private Map<String, String> expectedAppendOnlyTriggers() {
        Map<String, String> triggers = new TreeMap<>();
        triggers.put(
                AppendOnlySchemaGuardInitializer.REVIEW_AUDIT_EVENTS_TABLE,
                AppendOnlySchemaGuardInitializer.REVIEW_AUDIT_EVENTS_TRIGGER
        );
        triggers.put(
                AppendOnlySchemaGuardInitializer.REVIEW_EVIDENCE_TABLE,
                AppendOnlySchemaGuardInitializer.REVIEW_EVIDENCE_TRIGGER
        );
        return triggers;
    }

    private ReviewRuntimeManifestDTO.GroundingPolicySnapshot buildGroundingPolicySnapshot() {
        return ReviewRuntimeManifestDTO.GroundingPolicySnapshot.builder()
                .policyVersion(ReviewGroundingPolicyService.POLICY_VERSION)
                .minSeverity(ReviewGroundingPolicyService.MIN_REQUIRED_SEVERITY)
                .requiredEvidenceTypes(List.of("SOURCE_CODE"))
                .requireSourceAnchors(true)
                .build();
    }

    private ReviewRuntimeManifestDTO.SettingsSnapshot buildSettingsSnapshot() {
        SettingsDTO settings = null;
        if (systemConfigService != null) {
            try {
                settings = systemConfigService.getSettings();
            } catch (Exception ignored) {
                settings = null;
            }
        }
        String projectRoot = settings != null ? settings.getProjectRoot() : null;
        return ReviewRuntimeManifestDTO.SettingsSnapshot.builder()
                .projectRootHash(projectRoot != null && !projectRoot.isBlank() ? hashService.sha256Hex(projectRoot) : null)
                .projectRootConfigured(projectRoot != null && !projectRoot.isBlank())
                .ruleStandard(settings != null ? settings.getRuleStandard() : null)
                .rulePreset(settings != null ? settings.getRulePreset() : null)
                .includePaths(settings != null ? settings.getIncludePaths() : null)
                .excludePaths(settings != null ? settings.getExcludePaths() : null)
                .maxIssues(settings != null ? settings.getMaxIssues() : null)
                .ruleCategories(settings != null && settings.getRuleCategories() != null
                        ? new TreeMap<>(settings.getRuleCategories()) : Map.of())
                .ruleWeights(settings != null && settings.getRuleWeights() != null
                        ? new TreeMap<>(settings.getRuleWeights()) : Map.of())
                .build();
    }

    private String env(String key, String defaultValue) {
        return environment != null ? environment.getProperty(key, defaultValue) : defaultValue;
    }

    private Boolean envBoolean(String key, boolean defaultValue) {
        return environment != null ? environment.getProperty(key, Boolean.class, defaultValue) : defaultValue;
    }

    private Integer envInteger(String key, int defaultValue) {
        return environment != null ? environment.getProperty(key, Integer.class, defaultValue) : defaultValue;
    }

    private String hostOf(String value) {
        try {
            URI uri = URI.create(value);
            return uri.getHost() != null ? uri.getHost() : value;
        } catch (Exception e) {
            return "unparseable";
        }
    }
}
