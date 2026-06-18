# 总体设计

详细技术方案见 `docs/superpowers/specs/2026-06-18-rag-chunking-redesign.md`。本任务只维护总体拆分、并行边界和最终集成标准。

## 架构方向

`KnowledgeDocument` 保留为源文档，新增 `KnowledgeChunk` 作为可检索证据。上传、更新、启动重建都会生成 chunks，并把 chunks 写入数据库、向量库和 BM25 chunk 索引。

检索流程：

```text
query
 -> vector search chunks
 -> BM25 search chunks
 -> merge by chunkId
 -> score and rerank
 -> RetrievedKnowledgeChunk
```

## 并行实现边界

为了减少冲突，并行实现只允许子任务写入各自拥有的文件：

- 模型与重建服务：新增实体、Repository、配置、重建服务、数据库脚本。
- Splitter：`StructuredDocumentChunker` 及必要 helper。
- 检索：BM25 chunk index、retriever、candidate 映射。
- 测试评估：测试类和 `tools/rag_eval`。

`KnowledgeBaseService`、`KnowledgeVectorOperations` 这类跨模块集成文件如果需要多个子任务共同依赖，由主线程最后串行整合。

## 数据兼容

旧文档表和 UI 文档详情不破坏。旧 `search()` 继续返回文档级结果，但内部可从 top chunks 去重映射回 `KnowledgeDocument`。

## 风险

- 并行改动产生冲突：用文件所有权规避，共享文件主线程处理。
- chunk 过多导致成本上升：通过 `targetTokens`、`hardMaxTokens` 控制。
- 旧向量残留：重建服务必须先按 documentId 清理旧向量。
