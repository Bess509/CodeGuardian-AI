package com.codeguardian.service.provenance;

import com.codeguardian.config.AIConfigProperties;
import com.codeguardian.config.AppendOnlySchemaGuardInitializer;
import com.codeguardian.config.AuditSigningProperties;
import com.codeguardian.dto.ReviewRuntimeManifestDTO;
import com.codeguardian.service.rag.KnowledgeBaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewRuntimeManifestServiceTest {

    @Test
    void should_build_sanitized_runtime_manifest_with_self_hash() throws Exception {
        ProvenanceHashService hashService = new ProvenanceHashService(new ObjectMapper().findAndRegisterModules());
        AIConfigProperties ai = new AIConfigProperties();
        ai.setEnabled(true);
        ai.setProvider("OPENAI");
        AIConfigProperties.ProviderConfig provider = new AIConfigProperties.ProviderConfig();
        provider.setEnabled(true);
        provider.setApiKey("sk-secret");
        provider.setBaseUrl("https://api.openai.com/v1");
        provider.setModel("gpt-test");
        ai.setProviders(Map.of("OPENAI", provider));
        AuditSigningProperties signing = new AuditSigningProperties();
        signing.setEnabled(true);
        signing.setSecret("audit-secret");
        signing.setKeyId("unit-key");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.application.name", "code-review-ai-agent")
                .withProperty("app.rag.vectorize-on-startup", "true")
                .withProperty("app.rag.vectorstore.pgvector.dimensions", "384");
        ReviewRuntimeManifestService service = new ReviewRuntimeManifestService(
                ai,
                signing,
                null,
                environment,
                hashService
        );
        KnowledgeBaseService knowledgeBaseService = Mockito.mock(KnowledgeBaseService.class);
        Mockito.when(knowledgeBaseService.getCorpusFingerprint()).thenReturn("kb-fingerprint");
        Mockito.when(knowledgeBaseService.getLoadedDocumentCount()).thenReturn(12);
        java.lang.reflect.Field field = ReviewRuntimeManifestService.class.getDeclaredField("knowledgeBaseService");
        field.setAccessible(true);
        field.set(service, knowledgeBaseService);

        ReviewRuntimeManifestDTO manifest = service.buildManifest();
        String json = hashService.canonicalJson(manifest);

        assertEquals(ReviewRuntimeManifestService.MANIFEST_VERSION, manifest.getManifestVersion());
        assertNotNull(manifest.getManifestHash());
        assertTrue(service.verifyManifestHash(manifest));
        assertEquals("api.openai.com", manifest.getAi().getProviders().get("OPENAI").getBaseUrlHost());
        assertTrue(Boolean.TRUE.equals(manifest.getAi().getProviders().get("OPENAI").getApiKeyConfigured()));
        assertTrue(Boolean.TRUE.equals(manifest.getAudit().getSigningSecretConfigured()));
        assertTrue(Boolean.TRUE.equals(manifest.getRag().getVectorizeOnStartup()));
        assertEquals("kb-fingerprint", manifest.getRag().getKnowledgeBaseFingerprint());
        assertEquals(12, manifest.getRag().getKnowledgeBaseDocumentCount());
        assertEquals("codeguardian-db-append-only-guards-v1", manifest.getDatabaseGuards().getGuardVersion());
        assertFalse(Boolean.TRUE.equals(manifest.getDatabaseGuards().getQuerySupported()));
        assertEquals("jdbc_template_unavailable", manifest.getDatabaseGuards().getVerificationReason());
        assertTrue(Boolean.TRUE.equals(manifest.getGroundingPolicy().getRequireSourceAnchors()));
        assertFalse(json.contains("sk-secret"));
        assertFalse(json.contains("audit-secret"));

        manifest.getRag().setReviewRetrievalTopK(9);
        assertFalse(service.verifyManifestHash(manifest));
    }

    @Test
    void should_include_database_append_only_guard_status_in_manifest_hash() throws Exception {
        ProvenanceHashService hashService = new ProvenanceHashService(new ObjectMapper().findAndRegisterModules());
        ReviewRuntimeManifestService service = new ReviewRuntimeManifestService(hashService);
        JdbcTemplate jdbcTemplate = Mockito.mock(JdbcTemplate.class);
        Mockito.when(jdbcTemplate.queryForObject(
                        Mockito.contains("FROM pg_proc"),
                        Mockito.eq(Integer.class),
                        Mockito.eq(AppendOnlySchemaGuardInitializer.GUARD_FUNCTION_NAME)
                ))
                .thenReturn(1);
        Mockito.when(jdbcTemplate.queryForObject(
                        Mockito.contains("FROM pg_trigger"),
                        Mockito.eq(Integer.class),
                        Mockito.eq(AppendOnlySchemaGuardInitializer.REVIEW_EVIDENCE_TABLE),
                        Mockito.eq(AppendOnlySchemaGuardInitializer.REVIEW_EVIDENCE_TRIGGER),
                        Mockito.eq(AppendOnlySchemaGuardInitializer.REVIEW_AUDIT_EVENTS_TABLE),
                        Mockito.eq(AppendOnlySchemaGuardInitializer.REVIEW_AUDIT_EVENTS_TRIGGER),
                        Mockito.eq("%" + AppendOnlySchemaGuardInitializer.GUARD_FUNCTION_NAME + "%")
                ))
                .thenReturn(2);
        java.lang.reflect.Field field = ReviewRuntimeManifestService.class.getDeclaredField("jdbcTemplate");
        field.setAccessible(true);
        field.set(service, jdbcTemplate);

        ReviewRuntimeManifestDTO manifest = service.buildManifest();

        assertTrue(Boolean.TRUE.equals(manifest.getDatabaseGuards().getQuerySupported()));
        assertTrue(Boolean.TRUE.equals(manifest.getDatabaseGuards().getAppendOnlyGuardsInstalled()));
        assertEquals(2, manifest.getDatabaseGuards().getInstalledTriggerCount());
        assertEquals("ok", manifest.getDatabaseGuards().getVerificationReason());
        assertEquals(AppendOnlySchemaGuardInitializer.GUARD_FUNCTION_NAME,
                manifest.getDatabaseGuards().getGuardFunctionName());
        assertEquals(AppendOnlySchemaGuardInitializer.REVIEW_AUDIT_EVENTS_TRIGGER,
                manifest.getDatabaseGuards().getExpectedTriggers()
                        .get(AppendOnlySchemaGuardInitializer.REVIEW_AUDIT_EVENTS_TABLE));
        assertTrue(service.verifyManifestHash(manifest));

        manifest.getDatabaseGuards().setInstalledTriggerCount(1);
        assertFalse(service.verifyManifestHash(manifest));
    }
}
