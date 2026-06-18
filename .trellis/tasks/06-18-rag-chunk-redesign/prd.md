# RAG Chunk 级检索重构

## 目标

将知识库 RAG 从“文档级检索”升级为“chunk 级检索”。切分、向量索引、BM25、重排、证据和审计链路都必须围绕同一个 `chunkId` 工作，避免关键词未识别时不切分、固定块过大、同一文档多个 chunk 被合并等问题。

## 范围

- 新增持久化 `KnowledgeChunk` 模型和 chunk 重建机制。
- 重构 `StructuredDocumentChunker`，实现“标题/规则边界优先，段落/句子/token 滑窗兜底”。
- 将 BM25 和检索融合改为 chunk 级。
- 增加配置、测试和评估指标。
- 保留 `KnowledgeDocument` 作为源文档和旧 `search()` 兼容入口。

## 子任务地图

- `06-18-rag-chunk-model-rebuild`：数据模型、配置、数据库脚本、重建服务。
- `06-18-rag-structured-splitter`：结构优先 splitter 和切分元数据。
- `06-18-rag-chunk-retrieval-bm25`：chunk 级 BM25、检索融合、rerank 输入。
- `06-18-rag-chunk-tests-eval`：测试覆盖和离线评估指标。

## 并行约束

- 子任务必须遵守各自 `implement.md` 中的文件所有权。
- 不允许两个 subagent 同时修改同一个生产文件。
- 共享集成点由主线程完成或在单独串行阶段完成。
- 已存在的未归属工作区改动不得被回滚。

## 验收标准

- [ ] `knowledge_chunk` 持久化模型可保存 chunk 级证据。
- [ ] 文档内容变化、切分策略变化或配置 hash 变化时，会删除旧 chunk/旧向量并重建。
- [ ] 未识别到规则关键词的长文本仍会继续按自然边界/token 滑窗切分。
- [ ] 向量和 BM25 都以 chunk 为单位召回。
- [ ] 同一文档下多个 chunk 不会在候选阶段被折叠。
- [ ] Reranker 输入是 chunk 级证据。
- [ ] 关键单元测试和相关集成测试通过。
