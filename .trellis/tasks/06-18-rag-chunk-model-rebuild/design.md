# 设计

`KnowledgeChunk` 是 `KnowledgeDocument` 的派生检索实体。第一版按文档粒度重建 chunks，不做局部 diff。

重建判断：

```text
chunks missing
source_content_hash != document.content_hash
chunk_strategy != current strategy
chunk_config_hash != current config hash
```

重建服务应提供清晰方法边界：

```java
boolean chunksStale(KnowledgeDocument document)
void rebuildDocumentChunks(KnowledgeDocument document)
```

如果 splitter/vector 依赖尚未接入，可以通过私有 TODO-free 方法名和依赖接口表达边界，不写占位注释。
