# RAG Chunk 数据模型与重建服务

## 目标

新增 chunk 级持久化模型和文档级重建服务，让每个知识库文档都能派生出可检索、可重建、可清理的 `KnowledgeChunk`。

## 文件所有权

可修改：

- `src/main/java/com/codeguardian/entity/KnowledgeChunk.java` 或现有实体包下等价新文件
- `src/main/java/com/codeguardian/repository/KnowledgeChunkRepository.java`
- `src/main/java/com/codeguardian/service/rag/ChunkingProperties.java`
- `src/main/java/com/codeguardian/service/rag/KnowledgeChunkRebuildService.java`
- `database/schema.sql`
- 必要的模型/配置测试新文件

不得修改：

- `StructuredDocumentChunker.java`
- `KnowledgeRetriever.java`
- `Bm25Index.java`
- `KnowledgeBaseService.java`
- `KnowledgeVectorOperations.java`

共享集成由主线程处理。

## 需求

- `KnowledgeChunk` 包含 documentId、chunkIndex、content、headingPath、ruleIds、chunkStrategy、chunkConfigHash、sourceContentHash、charStart/end、tokenCount、overlapTokens、metadata、时间字段。
- Repository 支持按 `documentId` 查询和删除。
- 配置类暴露 `strategyVersion`、`targetTokens`、`hardMaxTokens`、`overlapTokens`、`minChunkTokens`、`maxRuleIdsPerChunk`、`maxHeadingLength`。
- 重建服务提供 stale 判断和文档级重建入口，但可先通过接口/方法占位等待主线程接入 splitter/vector。

## 验收标准

- [ ] 新实体和 Repository 编译通过。
- [ ] 数据库脚本包含 `knowledge_chunk` 表。
- [ ] stale 判断覆盖 source hash、strategy、config hash。
- [ ] 不修改其他子任务拥有的文件。
