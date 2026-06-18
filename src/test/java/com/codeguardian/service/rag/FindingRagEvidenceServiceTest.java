package com.codeguardian.service.rag;

import com.codeguardian.entity.Finding;
import com.codeguardian.service.provenance.EvidenceDraft;
import com.codeguardian.service.provenance.ProvenanceHashService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FindingRagEvidenceServiceTest {

    @Test
    void retrieveForFinding_ShouldBuildFindingScopedEvidence() {
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        ProvenanceHashService hashService = mock(ProvenanceHashService.class);
        FindingRagEvidenceService service = new FindingRagEvidenceService(knowledgeBaseService, hashService);
        Finding finding = finding();
        RetrievedKnowledgeChunk chunk = RetrievedKnowledgeChunk.builder()
                .chunkId("doc1::chunk-1")
                .sourceDocumentId("doc1")
                .sourceRef("knowledge://document/doc1#chunk=doc1::chunk-1")
                .title("SQL Injection Rule")
                .content("Use prepared statements.")
                .retrievalMode("VECTOR_ONLY")
                .rank(1)
                .score(0.42d)
                .metadata(Map.of(
                        "rule_ids", List.of("RULE-SQL-001"),
                        "reranked", true,
                        "reranker", "cross_encoder",
                        "rerankerModel", "test-reranker",
                        "crossEncoderScore", 0.93d,
                        "fusionScoreBeforeRerank", 0.18d,
                        "finalRerankScore", 0.90d,
                        "rerankCandidateCount", 4
                ))
                .build();

        when(knowledgeBaseService.searchSnippetChunks(anyString(), eq(1))).thenReturn(List.of(chunk));
        when(hashService.sha256Hex(anyString())).thenAnswer(invocation -> "hash-" + invocation.getArgument(0, String.class).length());

        List<EvidenceDraft> drafts = service.retrieveForFinding(
                finding,
                "src/UserDao.java",
                "Java",
                "class UserDao {\n  void find(String name) {\n    statement.execute(\"select \" + name);\n  }\n}");

        assertEquals(1, drafts.size());
        EvidenceDraft draft = drafts.get(0);
        assertEquals("RAG_SNIPPET", draft.getEvidenceType());
        assertEquals("KnowledgeBaseService", draft.getSourceName());
        assertEquals("doc1::chunk-1", draft.getLocator());
        assertEquals("FINDING", draft.getMetadata().get("retrievalScope"));
        assertEquals("SQL Injection", draft.getMetadata().get("findingTitle"));
        assertEquals("VECTOR_ONLY", draft.getMetadata().get("retrievalMode"));
        Map<?, ?> rerankAudit = (Map<?, ?>) draft.getMetadata().get("rerankAudit");
        assertEquals(true, rerankAudit.get("applied"));
        assertEquals("test-reranker", rerankAudit.get("rerankerModel"));
        assertEquals(0.93d, (Double) rerankAudit.get("topCrossEncoderScore"));
    }

    @Test
    void buildQuery_ShouldIncludeFindingFieldsAndSourceSnippet() {
        FindingRagEvidenceService service = new FindingRagEvidenceService(
                mock(KnowledgeBaseService.class),
                mock(ProvenanceHashService.class));

        String query = service.buildQuery(finding(), "src/UserDao.java", "Java",
                "line1\nline2\nstatement.execute(sql)\nline4");

        assertTrue(query.contains("Language: Java"));
        assertTrue(query.contains("Rule Categories: SECURITY"));
        assertTrue(query.contains("Finding Title: SQL Injection"));
        assertTrue(query.contains("Source Snippet:"));
        assertTrue(query.contains("statement.execute"));
    }

    private Finding finding() {
        return Finding.builder()
                .id(99L)
                .title("SQL Injection")
                .description("String concatenation is used in SQL execution.")
                .suggestion("Use prepared statements.")
                .category("SECURITY")
                .source("AI")
                .startLine(3)
                .endLine(3)
                .build();
    }
}
