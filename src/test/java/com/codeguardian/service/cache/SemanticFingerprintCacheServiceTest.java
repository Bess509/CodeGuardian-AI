package com.codeguardian.service.cache;

import com.codeguardian.config.ReviewCacheProperties;
import com.codeguardian.entity.Finding;
import com.codeguardian.enums.ModelProviderEnum;
import com.codeguardian.service.rag.KnowledgeBaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SemanticFingerprintCacheServiceTest {

    @Test
    void should_return_cached_findings_when_exact_match() throws Exception {
        ReviewCacheProperties props = new ReviewCacheProperties();
        props.setEnabled(true);
        props.setPromptVersion("p1");
        props.setNamespaceVersion("v1");
        props.setTtlDays(7);

        ObjectMapper om = new ObjectMapper();

        StringRedisTemplate redis = Mockito.mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = Mockito.mock(ValueOperations.class);
        SetOperations<String, String> setOps = Mockito.mock(SetOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForSet()).thenReturn(setOps);

        ObjectProvider<StringRedisTemplate> provider = Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);

        SemanticFingerprintCacheService service = new SemanticFingerprintCacheService(props, om, provider);

        String code = "public class A { int x = 1; }";
        String normalized = SemanticFingerprintCacheService.SemanticFingerprintNormalizer.normalize(code, "java");
        String exactHash = SemanticFingerprintCacheService.SemanticFingerprintCalculator.sha256Hex(normalized);
        long simHash = SemanticFingerprintCacheService.SemanticFingerprintCalculator.simHash64(normalized);
        String scopePrefix = "review:fp:v1:p1:java:QWEN:rag1:kb_unavailable";
        String exactKey = scopePrefix + ":exact:" + exactHash;

        SemanticFingerprintCacheService.CacheEntry entry = new SemanticFingerprintCacheService.CacheEntry();
        entry.setExactHash(exactHash);
        entry.setSimHash64(simHash);
        entry.setBlockStartLine(1);

        SemanticFingerprintCacheService.CachedFinding cf = new SemanticFingerprintCacheService.CachedFinding();
        cf.setSeverity(2);
        cf.setTitle("t");
        cf.setLocation("L1");
        cf.setStartLine(0);
        cf.setEndLine(0);
        cf.setDescription("d");
        cf.setSuggestion("s");
        cf.setCategory("BUG");
        cf.setSource("AI");
        entry.setFindings(List.of(cf));

        when(valueOps.get(exactKey)).thenReturn(om.writeValueAsString(entry));

        Optional<List<Finding>> cached = service.tryGetCachedFindings(code, "Java", ModelProviderEnum.QWEN, true, 1);

        assertTrue(cached.isPresent());
        assertEquals(1, cached.get().size());
        assertEquals("t", cached.get().get(0).getTitle());
        verify(setOps, never()).members(anyString());
    }

    @Test
    void should_return_cached_findings_when_similar_match() throws Exception {
        ReviewCacheProperties props = new ReviewCacheProperties();
        props.setEnabled(true);
        props.setPromptVersion("p1");
        props.setNamespaceVersion("v1");
        props.getSimhash().setMaxHammingDistance(3);
        props.getSimhash().setMaxCandidates(50);
        props.getSimhash().setSegments(4);

        ObjectMapper om = new ObjectMapper();

        StringRedisTemplate redis = Mockito.mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = Mockito.mock(ValueOperations.class);
        SetOperations<String, String> setOps = Mockito.mock(SetOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForSet()).thenReturn(setOps);

        ObjectProvider<StringRedisTemplate> provider = Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);

        SemanticFingerprintCacheService service = new SemanticFingerprintCacheService(props, om, provider);

        String code = "public class A { int x = 1; String s = \"a\"; }";
        String normalized = SemanticFingerprintCacheService.SemanticFingerprintNormalizer.normalize(code, "java");
        String exactHash = SemanticFingerprintCacheService.SemanticFingerprintCalculator.sha256Hex(normalized);
        long simHash = SemanticFingerprintCacheService.SemanticFingerprintCalculator.simHash64(normalized);

        String scopePrefix = "review:fp:v1:p1:java:QWEN:rag1:kb_unavailable";
        String exactKey = scopePrefix + ":exact:" + exactHash;

        when(valueOps.get(anyString())).thenReturn(null);

        List<String> bucketKeys = SemanticFingerprintCacheService.SemanticFingerprintCalculator.bucketKeys(scopePrefix, simHash, 4);
        for (String bucketKey : bucketKeys) {
            when(setOps.members(bucketKey)).thenReturn(Set.of(exactHash));
        }

        SemanticFingerprintCacheService.CacheEntry entry = new SemanticFingerprintCacheService.CacheEntry();
        entry.setExactHash(exactHash);
        entry.setSimHash64(simHash);
        entry.setBlockStartLine(1);

        SemanticFingerprintCacheService.CachedFinding cf = new SemanticFingerprintCacheService.CachedFinding();
        cf.setSeverity(1);
        cf.setTitle("hit");
        cf.setLocation("L1");
        cf.setStartLine(0);
        cf.setEndLine(0);
        cf.setDescription("d");
        cf.setSource("AI");
        entry.setFindings(List.of(cf));

        when(valueOps.get(exactKey)).thenReturn(om.writeValueAsString(entry));

        Optional<List<Finding>> cached = service.tryGetCachedFindings(code, "Java", ModelProviderEnum.QWEN, true, 1);

        assertTrue(cached.isPresent());
        assertEquals("hit", cached.get().get(0).getTitle());
    }

    @Test
    void should_store_findings_write_exact_and_buckets() {
        ReviewCacheProperties props = new ReviewCacheProperties();
        props.setEnabled(true);
        props.setPromptVersion("p1");
        props.setNamespaceVersion("v1");
        props.setTtlDays(7);

        ObjectMapper om = new ObjectMapper();

        StringRedisTemplate redis = Mockito.mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = Mockito.mock(ValueOperations.class);
        SetOperations<String, String> setOps = Mockito.mock(SetOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForSet()).thenReturn(setOps);

        ObjectProvider<StringRedisTemplate> provider = Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);

        SemanticFingerprintCacheService service = new SemanticFingerprintCacheService(props, om, provider);

        Finding f = new Finding();
        f.setSeverity(1);
        f.setTitle("t");
        f.setLocation("L1");
        f.setStartLine(10);
        f.setEndLine(10);
        f.setDescription("d");
        f.setSource("AI");

        String code = "public class A { int x = 1; }";
        service.storeFindings(code, "Java", ModelProviderEnum.QWEN, true, 1, List.of(f));

        verify(valueOps).set(anyString(), anyString(), any(Duration.class));
        verify(setOps, Mockito.atLeastOnce()).add(anyString(), anyString());
        verify(redis, Mockito.atLeastOnce()).expire(anyString(), any(Duration.class));
    }

    @Test
    void should_include_knowledge_base_fingerprint_in_rag_cache_scope() throws Exception {
        ReviewCacheProperties props = new ReviewCacheProperties();
        props.setEnabled(true);
        props.setPromptVersion("p1");
        props.setNamespaceVersion("v1");
        props.setTtlDays(7);

        ObjectMapper om = new ObjectMapper();

        StringRedisTemplate redis = Mockito.mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = Mockito.mock(ValueOperations.class);
        SetOperations<String, String> setOps = Mockito.mock(SetOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForSet()).thenReturn(setOps);

        ObjectProvider<StringRedisTemplate> provider = Mockito.mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);

        SemanticFingerprintCacheService service = new SemanticFingerprintCacheService(props, om, provider);
        KnowledgeBaseService knowledgeBaseService = Mockito.mock(KnowledgeBaseService.class);
        when(knowledgeBaseService.getCorpusFingerprint()).thenReturn("kbhash-a", "kbhash-b");
        java.lang.reflect.Field field = SemanticFingerprintCacheService.class.getDeclaredField("knowledgeBaseService");
        field.setAccessible(true);
        field.set(service, knowledgeBaseService);

        Finding f = new Finding();
        f.setSeverity(1);
        f.setTitle("t");
        f.setLocation("L1");
        f.setStartLine(10);
        f.setEndLine(10);
        f.setDescription("d");
        f.setSource("AI");

        String code = "public class A { int x = 1; }";
        service.storeFindings(code, "Java", ModelProviderEnum.QWEN, true, 1, List.of(f));
        service.tryGetCachedFindings(code, "Java", ModelProviderEnum.QWEN, true, 1);

        verify(valueOps).set(argThat((String key) -> key.contains(":rag1:kb_kbhash-a:exact:")), anyString(), any(Duration.class));
        verify(valueOps).get(argThat((String key) -> key.contains(":rag1:kb_kbhash-b:exact:")));
    }
}
