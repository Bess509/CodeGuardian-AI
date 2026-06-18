# 实施计划

1. 阅读 backend 规范和现有 `KnowledgeRetriever`、`Bm25Index`。
2. 修改 candidate key，优先按 `chunkId`。
3. 提供 chunk 级 BM25 索引实现。
4. 调整 rerank 文本格式。
5. 添加/更新 retriever 测试。

## 禁止事项

- 不改 splitter。
- 不改数据库文件。
- 不提交代码。
