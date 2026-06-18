# 实施计划

1. 阅读 `.trellis/spec/backend/index.md` 和 database guidelines。
2. 查看现有 `KnowledgeDocument` 实体、Repository 和 JSON metadata 处理方式。
3. 新增 `KnowledgeChunk` 实体。
4. 新增 `KnowledgeChunkRepository`。
5. 新增 `ChunkingProperties`。
6. 新增 `KnowledgeChunkRebuildService` 的 stale 判断和删除旧 chunk 方法。
7. 更新 `database/schema.sql`。
8. 添加或更新定向测试。

## 禁止事项

- 不改 splitter 和 retriever。
- 不回滚已有工作区改动。
- 不提交代码。
