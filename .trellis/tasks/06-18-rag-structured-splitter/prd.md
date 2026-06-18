# RAG 结构优先切分器

## 目标

重构 `StructuredDocumentChunker`，让关键词/规则边界只是最高优先级边界；当没有识别到规则关键词时，仍按段落、句子和 token 滑窗兜底切分。

## 文件所有权

可修改：

- `src/main/java/com/codeguardian/service/rag/StructuredDocumentChunker.java`
- `src/main/java/com/codeguardian/service/rag/*Chunker*.java` 新 helper
- `src/test/java/com/codeguardian/service/rag/StructuredDocumentChunkerTest.java`

不得修改：

- `KnowledgeRetriever.java`
- `Bm25Index.java`
- `KnowledgeBaseService.java`
- 数据库脚本

## 需求

- 支持 Markdown 标题、编号标题、中文标题。
- 支持规则 ID、规范标签、中文自然语言规则起点。
- 未识别规则起点的长 section 必须继续切分。
- 超长块必须走 token/window 或近似 token 兜底，不能超过硬上限。
- chunk metadata 包含 `split_reason`、`heading_path`、`rule_ids`、`token_count`、char range。

## 验收标准

- [ ] 无规则关键词长文本会被切成多个 chunks。
- [ ] `第1条`、`禁止：`、`建议：`、`检查项：` 可作为规则边界。
- [ ] 单个超长段落不会生成超大 chunk。
- [ ] 现有 splitter 测试通过并覆盖新增行为。
