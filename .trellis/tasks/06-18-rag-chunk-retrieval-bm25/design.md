# 设计

检索候选身份从 `sourceDocId` 改为 `chunkId`。

```text
vector results -> RetrievalCandidate(chunkId)
BM25 results -> RetrievalCandidate(chunkId)
same chunkId -> fused
different chunkId -> independent candidates
```

BM25 索引文本组合：

```text
title
heading_path
rule_ids
category
content
```

如果 `KnowledgeChunk` 类型尚未合入，可先设计 `Bm25ChunkIndex` 使用最小内部 DTO 或 Spring AI `Document`，主线程后续统一成 `KnowledgeChunk`。
