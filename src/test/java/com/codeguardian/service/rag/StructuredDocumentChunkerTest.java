package com.codeguardian.service.rag;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredDocumentChunkerTest {

    @Test
    void split_ShouldPreserveHeadingRuleMetadataAndUseSmallOverlap() {
        String content = """
                # Security Rules

                RULE-SQL-001 SQL injection
                Use prepared statements for SQL queries.

                ## Secrets

                SAFE-SECRET-001 Do not hardcode secrets.
                API tokens must come from secret storage.
                """;

        List<Document> chunks = StructuredDocumentChunker.split("doc-a", content, Map.of("title", "Rules"));

        assertFalse(chunks.isEmpty());
        Document first = chunks.get(0);
        assertEquals("doc-a", first.getMetadata().get("source_doc_id"));
        assertEquals("structure_rule_sentence_token_v1", first.getMetadata().get("chunk_strategy"));
        assertEquals(chunks.size(), first.getMetadata().get("chunk_count"));
        assertTrue(first.getMetadata().containsKey("heading_path"));
        assertTrue(first.getMetadata().containsKey("rule_ids"));
        assertTrue(first.getMetadata().containsKey("split_reason"));
        assertTrue(first.getMetadata().containsKey("token_count"));
        assertTrue(first.getMetadata().containsKey("char_start"));
        assertTrue(first.getMetadata().containsKey("char_end"));
    }

    @Test
    void split_ShouldChunkLargeSections() {
        String repeated = "Use prepared statements and validate input.\n".repeat(300);

        List<Document> chunks = StructuredDocumentChunker.split("doc-b", "# Security\n" + repeated, Map.of());

        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(chunk ->
                Integer.valueOf(320).equals(chunk.getMetadata().get("chunk_overlap_chars"))));
    }

    @Test
    void split_ShouldFallbackToParagraphBoundariesWhenNoRuleKeywordsExist() {
        String paragraph = """
                This section describes a review practice without any explicit rule identifier.
                The text is intentionally ordinary prose so the splitter must keep falling back
                through paragraph and sentence boundaries instead of relying on rule keywords.
                """;
        String content = "# Review Workflow\n\n" + (paragraph + "\n").repeat(40);

        List<Document> chunks = StructuredDocumentChunker.split("doc-unstructured", content, Map.of("title", "Workflow"));

        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(chunk -> chunk.getMetadata().containsKey("token_count")));
        assertTrue(chunks.stream().allMatch(chunk -> ((Number) chunk.getMetadata().get("token_count")).intValue() <= 800));
        assertTrue(chunks.stream().allMatch(chunk -> chunk.getMetadata().containsKey("char_start")));
        assertTrue(chunks.stream().allMatch(chunk -> chunk.getMetadata().containsKey("char_end")));
        assertTrue(chunks.stream().anyMatch(chunk -> "paragraph".equals(chunk.getMetadata().get("split_reason"))));
        assertTrue(chunks.stream().allMatch(chunk -> ((List<?>) chunk.getMetadata().get("rule_ids")).isEmpty()));
    }

    @Test
    void split_ShouldTreatChineseNaturalRuleStartsAsBoundaries() {
        String content = """
                # 代码检查

                第1条 数据库查询必须参数化
                所有数据库查询都要使用参数绑定，避免拼接用户输入。

                禁止：直接拼接 SQL
                审查时发现字符串拼接 SQL，应标记为高风险。

                建议：记录审计字段
                关键配置变更应记录操作者、时间和变更前后值。

                检查项：提交前运行自动化测试
                涉及规则引擎的改动必须包含对应单元测试。
                """;

        List<Document> chunks = StructuredDocumentChunker.split("doc-cn-rules", content, Map.of("title", "中文规则"));

        assertEquals(4, chunks.size());
        assertTrue(chunks.stream().allMatch(chunk -> "rule_boundary".equals(chunk.getMetadata().get("split_reason"))));
        assertTrue(chunks.get(0).getContent().contains("第1条"));
        assertTrue(chunks.get(1).getContent().contains("禁止："));
        assertTrue(chunks.get(2).getContent().contains("建议："));
        assertTrue(chunks.get(3).getContent().contains("检查项："));
    }

    @Test
    void split_ShouldUseTokenWindowForSingleHugeParagraph() {
        String content = "# Long Plain Paragraph\n\n" + "plainword ".repeat(2_400);

        List<Document> chunks = StructuredDocumentChunker.split("doc-long-paragraph", content, Map.of());

        assertTrue(chunks.size() > 2);
        assertTrue(chunks.stream().allMatch(chunk -> ((Number) chunk.getMetadata().get("token_count")).intValue() <= 800));
        assertTrue(chunks.stream().anyMatch(chunk -> "token_window".equals(chunk.getMetadata().get("split_reason"))));
        assertTrue(chunks.stream().allMatch(chunk ->
                ((Number) chunk.getMetadata().get("char_start")).intValue()
                        < ((Number) chunk.getMetadata().get("char_end")).intValue()));
    }

    @Test
    void split_ShouldTreatCgCodeRuleIdsAsChunkBoundaries() {
        String content = """
                CG-CODE-001 类名与接口名表达清晰职责
                类名使用名词或名词短语，接口名体现能力或边界。

                CG-CODE-002 方法名使用动词短语
                方法名应描述可观察行为，例如 calculateTotal。
                """;

        List<Document> chunks = StructuredDocumentChunker.split("doc-c", content, Map.of("title", "代码规范"));

        assertEquals(2, chunks.size());
        assertTrue(((List<?>) chunks.get(0).getMetadata().get("rule_ids")).contains("CG-CODE-001"));
        assertTrue(((List<?>) chunks.get(1).getMetadata().get("rule_ids")).contains("CG-CODE-002"));
    }
}
