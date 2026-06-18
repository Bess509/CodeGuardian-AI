# RAG Chunk 检索融合与 BM25

## 目标

将 BM25 和检索融合改为 chunk 级，确保同一文档的多个 chunk 可以作为独立候选进入融合和 rerank。

## 文件所有权

可修改：

- `src/main/java/com/codeguardian/service/rag/Bm25Index.java` 或新增 `Bm25ChunkIndex.java`
- `src/main/java/com/codeguardian/service/rag/KnowledgeRetriever.java`
- `src/main/java/com/codeguardian/service/rag/RetrievalCandidate.java`
- `src/main/java/com/codeguardian/service/rag/RetrievedKnowledgeChunk.java`
- 相关 retriever/BM25 测试

不得修改：

- `StructuredDocumentChunker.java`
- 数据库脚本
- `KnowledgeBaseService.java`
- `KnowledgeVectorOperations.java`

## 需求

- BM25 索引单位从 document 改为 chunk。
- 候选 key 优先使用 `chunkId`。
- `VECTOR_BM25_FUSED` 只表示同一个 chunk 同时命中向量和 BM25。
- reranker 输入包含 title、heading、rule IDs、category、content。
- 旧 `search()` 兼容路径可由主线程后续整合。

## 验收标准

- [ ] 同一文档两个 vector chunks 不会被合并。
- [ ] BM25 可以返回 chunk 级命中。
- [ ] reranker 接收 chunk 级候选。
- [ ] 不修改 splitter 或数据库文件。
