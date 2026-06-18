# 总体实施计划

## 步骤

1. 创建父子 Trellis 任务，并为每个子任务补齐 PRD/design/implement。
2. 配置每个子任务的 `implement.jsonl` 和 `check.jsonl`。
3. 只对无冲突文件范围并行启动 subagents。
4. 等待 subagent 完成后，主线程检查每个结果的文件列表和 diff。
5. 串行整合共享文件，例如 `KnowledgeBaseService`、`KnowledgeVectorOperations`、应用启动重建路径。
6. 运行定向测试，必要时再运行 `mvn test`。
7. 更新 Trellis 状态并准备中文 Git 提交。

## 并行策略

第一轮可并行：

- 模型与重建服务子任务：新增文件为主，不修改 splitter/retriever。
- Splitter 子任务：仅修改 splitter 相关文件。
- 检索/BM25 子任务：仅修改检索相关文件。
- 测试评估子任务：以测试和评估脚本为主，避免修改生产代码。

## 验证命令

```powershell
mvn -q "-Dtest=StructuredDocumentChunkerTest,KnowledgeBaseServiceTest,KnowledgeRetrieverRerankerTest" test
mvn test
```

如果外部依赖导致全量测试不可用，记录失败原因并至少运行受影响的定向测试。
