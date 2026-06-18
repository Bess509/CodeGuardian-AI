# 设计

切分管线：

```text
normalize
 -> section split by headings
 -> rule block split
 -> semantic boundary split
 -> token window fallback
 -> metadata build
```

第一版可以继续返回 Spring AI `Document`，但 metadata 必须为后续 `KnowledgeChunk` 映射准备好。等模型子任务合入后，主线程再决定是否将返回类型切换为 `KnowledgeChunk`。

关键原则：

- 结构边界优先于长度边界。
- 长度边界必须存在，避免关键词未识别时不切分。
- overlap 按 token 或近似 token 计算，不再只截固定字符。
