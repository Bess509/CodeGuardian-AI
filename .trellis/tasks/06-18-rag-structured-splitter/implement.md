# 实施计划

1. 阅读 backend 规范和现有 `StructuredDocumentChunkerTest`。
2. 保留当前公开调用方式，避免立即破坏 vector 写入。
3. 扩展标题和规则边界识别。
4. 增加语义边界和 token/window 兜底。
5. 丰富 metadata。
6. 更新 splitter 测试。

## 禁止事项

- 不改检索和 BM25。
- 不改数据库文件。
- 不提交代码。
