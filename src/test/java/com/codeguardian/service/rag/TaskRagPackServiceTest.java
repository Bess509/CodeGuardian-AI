package com.codeguardian.service.rag;

import com.codeguardian.dto.ReviewRequestDTO;
import com.codeguardian.entity.ReviewTask;
import com.codeguardian.service.CodeParserService;
import com.codeguardian.service.SystemConfigService;
import com.codeguardian.service.provenance.ProvenanceHashService;
import com.codeguardian.service.provenance.ReviewAuditService;
import com.codeguardian.service.provenance.ReviewProvenanceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskRagPackServiceTest {

    @Test
    void should_store_task_rag_pack_in_redis_memory_and_evidence_then_evict() {
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        CodeParserService codeParserService = mock(CodeParserService.class);
        SystemConfigService configService = mock(SystemConfigService.class);
        ReviewProvenanceService provenanceService = mock(ReviewProvenanceService.class);
        ReviewAuditService auditService = mock(ReviewAuditService.class);
        ProvenanceHashService hashService = mock(ProvenanceHashService.class);
        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);

        when(redisProvider.getIfAvailable()).thenReturn(redis);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(hashService.sha256Hex(anyString())).thenAnswer(invocation -> "a".repeat(64));
        when(knowledgeBaseService.searchSnippetChunks(anyString(), eq(8))).thenReturn(List.of(
                RetrievedKnowledgeChunk.builder()
                        .chunkId("chunk-1")
                        .sourceDocumentId("doc-1")
                        .title("Hardcoded secret")
                        .content("Do not hardcode passwords.")
                        .sourceRef("rules.md")
                        .retrievalMode("VECTOR_BM25_FUSED")
                        .rank(1)
                        .score(0.98d)
                        .build()
        ));

        TaskRagPackService service = new TaskRagPackService(
                knowledgeBaseService,
                codeParserService,
                configService,
                provenanceService,
                auditService,
                hashService,
                new ObjectMapper(),
                redisProvider
        );
        ReviewTask task = ReviewTask.builder().id(38L).scope("code-snippet").build();
        ReviewRequestDTO request = ReviewRequestDTO.builder()
                .reviewType("SNIPPET")
                .language("Java")
                .codeSnippet("class A { String password = \"123456\"; }")
                .enableRag(true)
                .build();

        Optional<TaskRagPack> pack = service.prepareAndStore(task, request);

        assertThat(pack).isPresent();
        assertThat(pack.get().getTaskId()).isEqualTo(38L);
        assertThat(pack.get().getItems()).hasSize(1);
        assertThat(service.find(38L)).isPresent();
        verify(valueOps).set(eq("codeguardian:task-rag-pack:38"),
                org.mockito.ArgumentMatchers.contains("chunk-1"),
                anyLong(),
                eq(TimeUnit.SECONDS));
        verify(provenanceService).persistTaskEvidence(eq(task), argThat(drafts ->
                drafts != null
                        && drafts.size() == 1
                        && "TASK_RAG_PACK".equals(drafts.get(0).getEvidenceType())
                        && drafts.get(0).getExcerpt().contains("chunk-1")
                        && drafts.get(0).getMetadata().containsKey("packContentHash")));
        verify(auditService).record(eq(38L), eq("TASK_RAG_PACK_CREATED"), eq("RAG"), eq("system"), anyString(), any());

        service.evict(38L);

        assertThat(service.find(38L)).isEmpty();
        verify(redis).delete("codeguardian:task-rag-pack:38");
        verify(auditService).record(eq(38L), eq("TASK_RAG_PACK_EVICTED"), eq("RAG"), eq("system"), anyString(), any());
    }
}
