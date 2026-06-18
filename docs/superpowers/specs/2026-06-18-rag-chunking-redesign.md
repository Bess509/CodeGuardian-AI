# RAG Chunk 级重构设计

## 目标

将 RAG 的摄取和检索从“文档级”升级为“chunk 级”。系统需要保留规则语义，避免过大的多规则混合 chunk，并确保向量检索、BM25、重排序、提示词证据和审计元数据都引用同一个 chunk 身份。

本设计解决三个当前问题：

- 规则感知切分过度依赖已识别的关键词或规则 ID。没有检测到规则边界时，整个章节可能不会继续切分。
- 固定字符上限可能产生过大的 chunk，导致规则证据不够精确。
- 当前检索混用了 chunk 级向量结果和文档级 BM25，并且可能把同一文档下的多个 chunk 合并为一个候选。

## 范围

本次范围包含：

- 新增持久化的 `KnowledgeChunk` 模型。
- 使用结构、规则边界、语义边界和 token 滑窗兜底切分文档。
- 在向量库和 BM25 中都以 chunk 为单位建立索引。
- 按 `chunkId` 合并检索候选，而不是按源文档 ID 合并。
- 当源内容或切分策略过期时，重建该文档的全部 chunk。
- 为切分行为增加配置和测试。

第一版不包含：

- 基于 diff 的局部 chunk 更新。
- 浏览 chunk 的 UI 页面。
- 替换现有文档详情流程。

## 数据模型

保留 `KnowledgeDocument` 作为源文档实体。新增 `KnowledgeChunk` 作为可检索的证据实体。

关系：

```text
knowledge_document 1 -> N knowledge_chunk
```

建议的 `knowledge_chunk` 字段：

```text
id                  varchar primary key
document_id          varchar not null
chunk_index          int not null
title                varchar
content              text not null
heading_path         text
rule_ids             jsonb
chunk_strategy       varchar
chunk_config_hash    varchar
source_content_hash  varchar
char_start           int
char_end             int
token_count          int
overlap_tokens       int
metadata             jsonb
created_at           timestamp
updated_at           timestamp
```

索引：

```text
idx_knowledge_chunk_document_id
idx_knowledge_chunk_strategy
idx_knowledge_chunk_rule_ids      等实现按规则 ID 过滤时再添加
idx_knowledge_chunk_metadata      等实现按元数据过滤时再添加
```

`KnowledgeDocument.content` 继续保留全文，以兼容现有文档列表和文档详情行为。

## Chunk 身份

Chunk ID 应该尽量稳定，但当有意义的 chunk 内容变化时也应该变化。

推荐的 ID 材料：

```text
documentId + ":" + chunkIndex + ":" + charStart + ":" + contentHash
```

实现上可以基于这些材料生成命名 UUID。

含义：

- 同一文档、同一策略、同一内容、同一 chunk 位置：ID 稳定。
- 文档更新或 chunk 边界变化：生成新 ID。

## 过期规则

每个 chunk 存储：

```text
source_content_hash = hash(document.content)
chunk_strategy = current strategy name/version
chunk_config_hash = hash(chunking parameters)
```

满足任一条件时，文档的 chunks 视为过期：

```text
no chunks exist for document
chunk.source_content_hash != document.content_hash
chunk.chunk_strategy != currentChunkStrategy
chunk.chunk_config_hash != currentChunkConfigHash
```

过期时按文档粒度重建：

```text
1. 保留 KnowledgeDocument 记录。
2. 删除 documentId 对应的所有 KnowledgeChunk 记录。
3. 删除 metadata 指向 documentId 的所有 vector_store 记录。
4. 使用当前配置切分当前文档内容。
5. 保存新 chunks。
6. 将新 chunks 写入向量库。
7. 刷新 BM25 chunk 索引。
```

第一版不要实现局部 chunk diff。一次小的文档编辑也可能导致 char offset、overlap 和 chunk index 全部偏移，局部更新很容易留下过期向量。

## 配置

新增 chunking 配置：

```yaml
app:
  rag:
    chunking:
      strategy-version: structure_rule_sentence_token_v1
      target-tokens: 550
      hard-max-tokens: 800
      overlap-tokens: 100
      min-chunk-tokens: 80
      max-rule-ids-per-chunk: 2
      max-heading-length: 80
```

参数含义：

- `targetTokens`：首选 chunk 大小。切分器会尽量接近这个大小，同时保留语义边界。
- `hardMaxTokens`：硬上限。任何超过该上限的块都必须继续切分。
- `overlapTokens`：长块拆分时，相邻 chunk 之间携带的上下文 token 数。
- `minChunkTokens`：独立 chunk 的下限。更小的片段应尽量与相邻且兼容的内容合并。
- `maxRuleIdsPerChunk`：一个普通 chunk 最多合并的规则 ID 数，用来防止规则污染。

`chunk_strategy` 是生成 chunk 时持久化的策略/版本。它之所以有意义，是因为 chunk 会跨重启和部署长期存在。系统会把当前代码/配置中的值和持久化值比较，决定旧 chunk 是否可以复用。

## Splitter 架构

保留 `StructuredDocumentChunker` 作为公开门面，但将内部职责拆成更小的单元：

```text
StructuredDocumentChunker
 -> DocumentNormalizer
 -> SectionSplitter
 -> RuleBlockSplitter
 -> SemanticBoundarySplitter
 -> TokenWindowSplitter
 -> ChunkMetadataBuilder
```

门面签名：

```java
List<KnowledgeChunk> split(KnowledgeDocument document, ChunkingProperties properties)
```

如果向量库写入仍使用 Spring AI `Document`，应在向量操作边界将 `KnowledgeChunk` 转换为 `org.springframework.ai.document.Document`。

## 切分算法

### 1. Normalize

标准化换行和过多空行，同时保留代码块、列表和表格结构。

规则：

```text
\r\n -> \n
\r -> \n
3+ blank lines -> 2 blank lines
do not merge lines inside fenced code blocks
do not break Markdown table rows
```

第一版可以使用基于标准化文本的 offset，并记录：

```text
offset_basis = normalized_text
```

### 2. 按标题切分 Section

先识别标题，再识别规则。

标题模式：

```text
# / ## Markdown headings
1. Heading / 1.1 Heading
一、标题 / （一）标题
第1章 / 第 2 节 / 第三条
short all-caps English headings
```

避免误判：

```text
heading length <= maxHeadingLength
do not match inside code blocks
prefer lines surrounded by blank lines
avoid ordinary sentence lines ending with full punctuation
```

输出：

```text
Section {
  headingPath,
  text,
  charStart,
  charEnd
}
```

### 3. 切分 RuleBlock

在每个 section 内检测规则起点。

强 ID 模式：

```text
RULE-SQL-001
CG-CODE-001
CWE-89
OWASP-A03
P3C-...
SEC-...
SAFE-...
```

规范标签模式：

```text
【强制】
【推荐】
【参考】
[Mandatory]
[Recommended]
```

自然语言规则模式：

```text
规则1：
第1条
检查项：
禁止：
建议：
风险：
修复建议：
```

规则块在遇到下一条规则起点或下一个标题时结束。如果某个 section 没有任何规则起点，则把该 section 作为 `unstructured_block` 交给语义兜底层处理。

### 4. 组装普通 Chunks

对于较小的规则块：

```text
if block.tokens <= targetTokens:
  merge with previous compatible chunk when:
    same document
    same section
    combined tokens <= targetTokens
    combined rule ID count <= maxRuleIdsPerChunk
```

对于过大的块：

```text
if block.tokens > hardMaxTokens:
  split by semantic boundaries first
  if still too large, split by token window
```

默认目标行为：

- 大多数规则 chunk 只包含一个规则 ID。
- 非常短且相邻的规则可以共享一个 chunk，但最多到 `maxRuleIdsPerChunk`。
- 代码示例应尽量和最近的规则描述或修复建议保留在同一个 chunk 中。

### 5. 语义边界兜底

当没有检测到规则边界时，使用自然边界切分：

```text
blank-line paragraphs
Markdown list items
table row groups
Chinese sentence punctuation
English sentence punctuation
semicolon/colon boundaries
code line boundaries
```

这是和当前行为的关键区别：缺少规则关键词不再意味着“不切分”，只意味着切分器会继续退到优先级更低的边界。

### 6. Token 滑窗兜底

Token 滑窗是最后的硬保证。

伪逻辑：

```text
start = 0
while start < tokens.size:
  preferredEnd = start + targetTokens
  end = nearestBoundaryBetween(preferredEnd, start + hardMaxTokens)
  if no boundary:
    end = min(start + hardMaxTokens, tokens.size)

  emit tokens[start:end]
  if end == tokens.size:
    break
  start = max(end - overlapTokens, start + 1)
```

只要可能，优先在句子、段落、列表项或代码块边界结束。

## Chunk 元数据

每个 chunk 至少包含：

```text
chunk_id
document_id
chunk_index
heading_path
rule_ids
chunk_strategy
chunk_config_hash
source_content_hash
split_reason
token_count
char_start
char_end
overlap_tokens
parser
parserStrategy
category
```

`split_reason` 取值：

```text
heading
rule_boundary
paragraph
sentence
token_window
merged_small_block
```

这会显著降低排查难度。如果大量 chunks 都由 `token_window` 产生，说明规则边界检测很可能遗漏了重要的源文档模式。

## 向量库集成

向量库记录应该是 chunk 记录。

`Document.id` 应使用 `KnowledgeChunk.id`。

向量元数据应包含：

```text
chunk_id
source_doc_id
document_id
title
category
heading_path
rule_ids
chunk_index
chunk_strategy
chunk_config_hash
source_content_hash
token_count
char_start
char_end
split_reason
```

在为某个文档重写 chunks 前，需要先按文档 ID 删除旧向量记录。如果 Spring AI 的 `VectorStore` 抽象没有暴露按 metadata 删除的能力，则使用 `JdbcTemplate` 直接操作 `vector_store` 表。

## BM25 Chunk 索引

用 chunk 级 BM25 替换文档级 BM25。

新结构：

```java
class Bm25ChunkIndex {
    void rebuild(List<KnowledgeChunk> chunks)
    List<Integer> search(String query, int topK)
    KnowledgeChunk get(int index)
}
```

索引文本应组合：

```text
title
heading_path
rule_ids
category
content
```

这样包含规则 ID、分类、标题或自然语言的 query 都可以命中特定 chunk。

## 检索融合

检索应使用 chunk ID 作为候选身份。

流程：

```text
query
 -> vector search topN chunks
 -> BM25 chunk search topN chunks
 -> Map<chunkId, RetrievalCandidate>
 -> score with vector rank, BM25 rank, and small metadata boosts
 -> rerank when the configured reranker is enabled
 -> return RetrievedKnowledgeChunk list
```

候选 key：

```java
private String candidateKey(String sourceDocId, String chunkId) {
    if (chunkId != null && !chunkId.isBlank()) {
        return chunkId;
    }
    if (sourceDocId != null && !sourceDocId.isBlank()) {
        return sourceDocId;
    }
    return UUID.randomUUID().toString();
}
```

这可以防止同一文档下的多个 chunks 被折叠成一个结果。

检索模式：

```text
VECTOR_ONLY
BM25_ONLY
VECTOR_BM25_FUSED
```

`VECTOR_BM25_FUSED` 只表示同一个 chunk 同时被两个检索通道命中。

## 重排序

重排序输入应该是 chunk 级文本，而不是整篇文档片段。

建议的重排序文本：

```text
Title: ...
Heading: ...
Rule IDs: ...
Category: ...
Content:
...
```

现有 `RAG_RERANKER_MAX_TEXT_CHARS` 仍可用于限制重排序载荷。

## 兼容性

保留 `KnowledgeBaseService.search(String query, int topK)` 供旧调用方使用：它可以把 top chunks 映射回去重后的 `KnowledgeDocument`。

主要 RAG 流程应使用：

```java
searchSnippetChunks(String query, int topK)
```

并接收 chunk 级的 `RetrievedKnowledgeChunk` 记录。

## 服务与 Repository

新增：

```text
KnowledgeChunk
KnowledgeChunkRepository
ChunkingProperties
KnowledgeChunkService or KnowledgeChunkRebuildService
Bm25ChunkIndex
```

更新：

```text
StructuredDocumentChunker
KnowledgeVectorOperations
KnowledgeRetriever
KnowledgeBaseService
RetrievedKnowledgeChunk metadata mapping
TaskRagPackService if it assumes document-level details
FindingRagEvidenceService only if metadata assumptions break
```

建议的服务职责：

```text
KnowledgeBaseService
  负责文档上传、更新、删除，并调用 chunk 重建服务。

KnowledgeChunkRebuildService
  检测 stale chunks，删除旧 chunks/旧向量，切分当前内容，保存 chunks，
  写入向量，并重建或更新 BM25。

KnowledgeVectorOperations
  将 KnowledgeChunk 转换为 Spring AI Document，并处理向量清理/写入。

KnowledgeRetriever
  搜索向量和 BM25 chunk 索引，融合候选，重排序，并返回 chunks。
```

## 上传与更新流程

文档上传：

```text
parse file
save KnowledgeDocument
rebuild chunks for document
rebuild/update BM25 chunk index
```

文档更新：

```text
update KnowledgeDocument fields and content_hash
delete old chunks for document
delete old vectors for document
split current content
save new chunks
add new vectors
refresh BM25 chunk index
```

文档删除：

```text
delete KnowledgeDocument
delete KnowledgeChunk rows by cascade or explicit repository call
delete vector records by documentId
refresh BM25 chunk index
```

## 启动流程

应用启动时：

```text
load documents
for each document:
  if chunks missing or stale:
    rebuild chunks for that document
load all chunks
rebuild BM25 chunk index
vectorize missing chunks when app.rag.vectorize-on-startup is enabled
```

启动路径需要注意不要触发昂贵的全量向量化，除非 `app.rag.vectorize-on-startup` 允许。

## 测试

Chunker 测试：

- 没有规则关键词的长文本仍会被切成多个 chunks。
- 中文规则起点，例如 `第1条`、`禁止：`、`建议：`、`检查项：`，能产生规则边界。
- 超大的单段落会退到 token 滑窗切分。
- 普通 chunks 不超过 `hardMaxTokens`。
- 短 chunks 在低于 `minChunkTokens` 且兼容时会合并。
- 除非单个源块本身已经超过限制，否则 chunks 不超过 `maxRuleIdsPerChunk`。
- 元数据包含 `split_reason`、`heading_path`、`token_count`、`char_start` 和 `char_end`。

BM25 测试：

- BM25 返回 chunk 级匹配。
- title、heading path、rule IDs、category 和 content 中的 query 词都可检索。

Retriever 测试：

- 同一文档下的两个向量 chunks 会保留为独立候选。
- 向量和 BM25 只有在命中同一 chunk ID 时才融合。
- Reranker 接收 chunk 级候选。
- 旧版 `search()` 可以把 top chunks 映射回去重后的文档。

Service 测试：

- 上传会创建文档、chunks 和向量记录。
- 更新文档会删除旧 chunks/旧向量，并写入新的记录。
- strategy/config hash 变化会触发重建。
- 删除文档会移除 chunks 和向量记录。

## 评估

扩展 `tools/rag_eval`，增加：

```text
avg_chunk_tokens
p95_chunk_tokens
avg_rule_ids_per_chunk
chunks_without_detected_rule_boundary_rate
token_window_fallback_rate
same_document_multi_chunk_recall_rate
topK_unique_chunk_count
```

主要成功标准：

- top chunk 的平均 rule ID 数降低。
- P95 chunk 大小保持在配置的硬上限内。
- 召回率保持稳定或提升。
- token-window fallback rate 可见，不再被静默隐藏。
- 当 query 匹配同一文档下多个 chunks 时，能够召回多个独立 chunk。

## 风险

风险：chunk 数增加会提高向量化成本和存储成本。
缓解：使用可配置的 target/hard 限制，并监控 chunk 数。

风险：chunk 过小导致上下文丢失。
缓解：使用 overlap、`minChunkTokens` 和规则感知合并。

风险：更新后旧向量仍然残留。
缓解：写入重建 chunks 前，先按文档 ID 删除向量。

风险：修改切分逻辑后忘记提升 strategy version。
缓解：同时使用 `chunk_strategy` 和 `chunk_config_hash`；更新测试，验证参数变化时 stale chunks 会重建。

## 推荐实施顺序

1. 新增 `KnowledgeChunk` 实体、Repository 和 chunking 配置。
2. 重构 `StructuredDocumentChunker`，让它生成带元数据的 chunk 对象。
3. 新增 stale 检测和文档级 chunk 重建服务。
4. 更新向量写入和清理逻辑，让它们使用 chunks。
5. 用 `Bm25ChunkIndex` 替换文档级 BM25 索引。
6. 更新 retriever 候选身份和融合逻辑，使用 `chunkId`。
7. 保留旧文档搜索兼容性。
8. 添加测试并扩展离线评估指标。
