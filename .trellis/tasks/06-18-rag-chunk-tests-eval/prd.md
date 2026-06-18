# RAG Chunk 测试与评估

## 目标

补充自动化测试和离线评估指标，验证 chunk 级重构确实降低规则污染，并覆盖关键词未识别时的兜底切分。

## 文件所有权

可修改：

- `src/test/java/com/codeguardian/service/rag/*Test.java`
- `tools/rag_eval/**`

不得修改：

- `src/main/java/**` 生产代码
- `database/**`

## 需求

- 增加 splitter 行为测试建议或测试实现。
- 增加 retriever/BM25 chunk 级行为测试建议或测试实现。
- 扩展 `tools/rag_eval` 指标：avg chunk tokens、p95 chunk tokens、avg rule IDs per chunk、token window fallback rate、topK unique chunk count。

## 验收标准

- [ ] 测试覆盖缺少规则关键词仍切分。
- [ ] 测试覆盖同文档多 chunk 不合并。
- [ ] 评估报告能展示 chunk 大小和规则污染指标。
- [ ] 不修改生产代码。
