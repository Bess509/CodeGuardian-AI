# RAG Chunk-Level Redesign

## Goal

Upgrade RAG ingestion and retrieval from document-level behavior to chunk-level behavior.
The system should preserve rule semantics, avoid large mixed-rule chunks, and ensure
that vector search, BM25, reranking, prompt evidence, and audit metadata all refer to
the same chunk identity.

This design addresses three current issues:

- Rule-aware splitting depends too much on recognized keywords or IDs. When no rule
  boundary is detected, a whole section can remain unsplit.
- Fixed character limits can produce chunks that are too large for precise rule
  grounding.
- Retrieval currently mixes chunk-level vector results with document-level BM25 and
  can merge multiple chunks from the same document into one candidate.

## Scope

In scope:

- Add a persisted `KnowledgeChunk` model.
- Split documents into chunks using structure, rule boundaries, semantic boundaries,
  and token-window fallback.
- Index chunks in both vector store and BM25.
- Merge retrieval candidates by `chunkId`, not by source document ID.
- Rebuild all chunks for a document when the source content or chunking strategy is stale.
- Add configuration and tests for chunking behavior.

Out of scope for the first implementation:

- Partial diff-based chunk updates.
- UI screens for browsing chunks.
- Replacing the existing document detail flow.

## Data Model

Keep `KnowledgeDocument` as the source document entity. Add `KnowledgeChunk` as the
retrievable evidence entity.

Relationship:

```text
knowledge_document 1 -> N knowledge_chunk
```

Suggested `knowledge_chunk` columns:

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

Indexes:

```text
idx_knowledge_chunk_document_id
idx_knowledge_chunk_strategy
idx_knowledge_chunk_rule_ids      defer until rule-id filtering is implemented
idx_knowledge_chunk_metadata      defer until metadata filtering is implemented
```

`KnowledgeDocument.content` remains the full source text for compatibility with
existing document list/detail behavior.

## Chunk Identity

Chunk IDs should be stable but change when the meaningful chunk content changes.

Recommended key material:

```text
documentId + ":" + chunkIndex + ":" + charStart + ":" + contentHash
```

The implementation can use UUID name generation over that material.

This means:

- Same document, same strategy, same content, same chunk position: stable ID.
- Updated document or changed chunk boundary: new ID.

## Staleness Rules

Each chunk stores:

```text
source_content_hash = hash(document.content)
chunk_strategy = current strategy name/version
chunk_config_hash = hash(chunking parameters)
```

A document's chunks are stale when any of these conditions is true:

```text
no chunks exist for document
chunk.source_content_hash != document.content_hash
chunk.chunk_strategy != currentChunkStrategy
chunk.chunk_config_hash != currentChunkConfigHash
```

When stale, rebuild at document granularity:

```text
1. Keep the KnowledgeDocument row.
2. Delete all KnowledgeChunk rows for documentId.
3. Delete all vector_store records whose metadata points to documentId.
4. Split current document content with current configuration.
5. Save new chunks.
6. Add new chunks to the vector store.
7. Refresh BM25 chunk index.
```

Do not implement partial chunk diffing in the first version. It is too easy to leave
stale vectors behind because char offsets, overlap, and chunk indexes can shift after
a small document edit.

## Configuration

Add chunking properties:

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

Parameter meanings:

- `targetTokens`: preferred chunk size. The splitter tries to stay near this size
  while preserving semantic boundaries.
- `hardMaxTokens`: hard upper bound. Any block above this must be split further.
- `overlapTokens`: context carried from one chunk to the next when splitting a long
  block.
- `minChunkTokens`: lower bound for standalone chunks. Smaller pieces should be merged
  with adjacent compatible content when possible.
- `maxRuleIdsPerChunk`: maximum number of rule IDs to merge into one normal chunk.
  This prevents rule contamination.

`chunk_strategy` is the persisted strategy/version that generated a chunk. It only
matters because chunks survive across restarts and deployments. The current code/config
value is compared with the persisted value to decide whether old chunks can be reused.

## Splitter Architecture

Keep `StructuredDocumentChunker` as the public facade, but split responsibilities into
smaller internal units:

```text
StructuredDocumentChunker
 -> DocumentNormalizer
 -> SectionSplitter
 -> RuleBlockSplitter
 -> SemanticBoundarySplitter
 -> TokenWindowSplitter
 -> ChunkMetadataBuilder
```

Facade signature:

```java
List<KnowledgeChunk> split(KnowledgeDocument document, ChunkingProperties properties)
```

If using Spring AI `Document` for vector store writing, convert `KnowledgeChunk` to
`org.springframework.ai.document.Document` at the vector operation boundary.

## Splitting Algorithm

### 1. Normalize

Normalize line endings and excessive blank lines while preserving code blocks, lists,
and tables.

Rules:

```text
\r\n -> \n
\r -> \n
3+ blank lines -> 2 blank lines
do not merge lines inside fenced code blocks
do not break Markdown table rows
```

The first version can use offsets based on normalized text and store:

```text
offset_basis = normalized_text
```

### 2. Split Sections By Headings

Recognize headings before recognizing rules.

Heading patterns:

```text
# / ## Markdown headings
1. Heading / 1.1 Heading
一、标题 / （一）标题
第1章 / 第 2 节 / 第三条
short all-caps English headings
```

Avoid false positives:

```text
heading length <= maxHeadingLength
do not match inside code blocks
prefer lines surrounded by blank lines
avoid ordinary sentence lines ending with full punctuation
```

Output:

```text
Section {
  headingPath,
  text,
  charStart,
  charEnd
}
```

### 3. Split Rule Blocks

Within each section, detect rule starts.

Strong ID patterns:

```text
RULE-SQL-001
CG-CODE-001
CWE-89
OWASP-A03
P3C-...
SEC-...
SAFE-...
```

Normative label patterns:

```text
【强制】
【推荐】
【参考】
[Mandatory]
[Recommended]
```

Natural-language rule patterns:

```text
规则1：
第1条
检查项：
禁止：
建议：
风险：
修复建议：
```

A rule block ends when the next rule start or next heading starts. If a section has
no rule starts, send the section as an `unstructured_block` to semantic fallback.

### 4. Assemble Normal Chunks

For small rule blocks:

```text
if block.tokens <= targetTokens:
  merge with previous compatible chunk when:
    same document
    same section
    combined tokens <= targetTokens
    combined rule ID count <= maxRuleIdsPerChunk
```

For oversized blocks:

```text
if block.tokens > hardMaxTokens:
  split by semantic boundaries first
  if still too large, split by token window
```

Default target behavior:

- Most rule chunks should contain one rule ID.
- Very short adjacent rules may share a chunk, up to `maxRuleIdsPerChunk`.
- Code examples should remain with the nearest rule description or remediation text
  when possible.

### 5. Semantic Boundary Fallback

When rule boundaries are not detected, split using natural boundaries:

```text
blank-line paragraphs
Markdown list items
table row groups
Chinese sentence punctuation
English sentence punctuation
semicolon/colon boundaries
code line boundaries
```

This is the key difference from the current behavior: missing rule keywords no longer
means "do not split." It only means the splitter moves to lower-priority boundaries.

### 6. Token Window Fallback

Token-window splitting is the final hard guarantee.

Pseudo logic:

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

Prefer ending at sentence, paragraph, list item, or code-block boundaries when possible.

## Chunk Metadata

Every chunk should include:

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

`split_reason` values:

```text
heading
rule_boundary
paragraph
sentence
token_window
merged_small_block
```

This makes debugging much easier. If many chunks are created by `token_window`, rule
boundary detection is probably missing important source patterns.

## Vector Store Integration

Vector store records should be chunk records.

`Document.id` should be `KnowledgeChunk.id`.

Vector metadata should include:

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

Before rewriting chunks for a document, remove old vector records by document ID. If
the Spring AI `VectorStore` abstraction does not expose deletion by metadata, use
`JdbcTemplate` against the `vector_store` table.

## BM25 Chunk Index

Replace document-level BM25 with chunk-level BM25.

New shape:

```java
class Bm25ChunkIndex {
    void rebuild(List<KnowledgeChunk> chunks)
    List<Integer> search(String query, int topK)
    KnowledgeChunk get(int index)
}
```

Indexed text should combine:

```text
title
heading_path
rule_ids
category
content
```

This allows queries containing rule IDs, categories, headings, or natural language to
hit specific chunks.

## Retrieval Fusion

Retrieval should use chunk IDs as candidate identity.

Flow:

```text
query
 -> vector search topN chunks
 -> BM25 chunk search topN chunks
 -> Map<chunkId, RetrievalCandidate>
 -> score with vector rank, BM25 rank, and small metadata boosts
 -> rerank when the configured reranker is enabled
 -> return RetrievedKnowledgeChunk list
```

Candidate key:

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

This prevents multiple chunks from the same document being collapsed into one result.

Retrieval modes:

```text
VECTOR_ONLY
BM25_ONLY
VECTOR_BM25_FUSED
```

`VECTOR_BM25_FUSED` should only mean the same chunk was found by both retrieval paths.

## Reranking

Reranker input should be chunk-level text, not a full document snippet.

Suggested reranker text:

```text
Title: ...
Heading: ...
Rule IDs: ...
Category: ...
Content:
...
```

The existing `RAG_RERANKER_MAX_TEXT_CHARS` setting can still cap the rerank payload.

## Compatibility

Keep `KnowledgeBaseService.search(String query, int topK)` for old callers by mapping
top chunks back to unique `KnowledgeDocument` values.

Primary RAG flows should use:

```java
searchSnippetChunks(String query, int topK)
```

and receive chunk-level `RetrievedKnowledgeChunk` records.

## Services And Repositories

Add:

```text
KnowledgeChunk
KnowledgeChunkRepository
ChunkingProperties
KnowledgeChunkService or KnowledgeChunkRebuildService
Bm25ChunkIndex
```

Update:

```text
StructuredDocumentChunker
KnowledgeVectorOperations
KnowledgeRetriever
KnowledgeBaseService
RetrievedKnowledgeChunk metadata mapping
TaskRagPackService if it assumes document-level details
FindingRagEvidenceService only if metadata assumptions break
```

Suggested service responsibilities:

```text
KnowledgeBaseService
  Owns document upload/update/delete and calls chunk rebuild service.

KnowledgeChunkRebuildService
  Detects stale chunks, deletes old chunks/vectors, splits current content, saves chunks,
  writes vectors, and rebuilds or updates BM25.

KnowledgeVectorOperations
  Converts KnowledgeChunk to Spring AI Document and handles vector cleanup/write.

KnowledgeRetriever
  Searches vector and BM25 chunk indexes, fuses candidates, reranks, and returns chunks.
```

## Upload And Update Flow

Document upload:

```text
parse file
save KnowledgeDocument
rebuild chunks for document
rebuild/update BM25 chunk index
```

Document update:

```text
update KnowledgeDocument fields and content_hash
delete old chunks for document
delete old vectors for document
split current content
save new chunks
add new vectors
refresh BM25 chunk index
```

Document delete:

```text
delete KnowledgeDocument
delete KnowledgeChunk rows by cascade or explicit repository call
delete vector records by documentId
refresh BM25 chunk index
```

## Startup Flow

On startup:

```text
load documents
for each document:
  if chunks missing or stale:
    rebuild chunks for that document
load all chunks
rebuild BM25 chunk index
vectorize missing chunks when app.rag.vectorize-on-startup is enabled
```

The startup path should be careful not to trigger expensive full vectorization unless
`app.rag.vectorize-on-startup` allows it.

## Testing

Chunker tests:

- Long text with no rule keywords still splits into multiple chunks.
- Chinese rule starts such as `第1条`, `禁止：`, `建议：`, and `检查项：` create
  rule boundaries.
- Oversized single paragraphs fall back to token-window splitting.
- Normal chunks do not exceed `hardMaxTokens`.
- Short chunks merge when under `minChunkTokens` and compatible.
- Chunks do not exceed `maxRuleIdsPerChunk` unless a single source block already does.
- Metadata includes `split_reason`, `heading_path`, `token_count`, `char_start`, and
  `char_end`.

BM25 tests:

- BM25 returns chunk-level matches.
- Query terms in title, heading path, rule IDs, category, and content are searchable.

Retriever tests:

- Two vector chunks from the same document remain separate candidates.
- Vector and BM25 hits are fused only when they refer to the same chunk ID.
- Reranker receives chunk-level candidates.
- Legacy `search()` can map top chunks back to unique documents.

Service tests:

- Upload creates document, chunks, and vector records.
- Updating a document deletes old chunks/vectors and writes new ones.
- Changed strategy/config hash triggers rebuild.
- Deleting a document removes chunks and vector records.

## Evaluation

Extend `tools/rag_eval` with:

```text
avg_chunk_tokens
p95_chunk_tokens
avg_rule_ids_per_chunk
chunks_without_detected_rule_boundary_rate
token_window_fallback_rate
same_document_multi_chunk_recall_rate
topK_unique_chunk_count
```

Primary success criteria:

- Lower average rule IDs per top chunk.
- P95 chunk size stays within the configured hard maximum.
- Recall stays stable or improves.
- Token-window fallback rate is visible and not silently hidden.
- Same-document multiple chunk recall works when a query matches several chunks.

## Risks

Risk: Chunk count increases vectorization cost and storage.
Mitigation: Use configurable target/hard limits and monitor chunk counts.

Risk: Chunks become too small and lose context.
Mitigation: Use overlap, `minChunkTokens`, and rule-aware merging.

Risk: Old vectors remain after updates.
Mitigation: Delete vectors by document ID before writing rebuilt chunks.

Risk: Strategy version is not bumped after logic changes.
Mitigation: Use both `chunk_strategy` and `chunk_config_hash`; update tests to verify
stale chunks rebuild when parameters change.

## Recommended Implementation Order

1. Add `KnowledgeChunk` entity, repository, and chunking configuration.
2. Refactor `StructuredDocumentChunker` to produce chunk objects with metadata.
3. Add stale detection and document-level chunk rebuild service.
4. Update vector writing and cleanup to use chunks.
5. Replace BM25 document index with `Bm25ChunkIndex`.
6. Update retriever candidate identity and fusion to use `chunkId`.
7. Preserve legacy document search compatibility.
8. Add tests and extend offline evaluation metrics.
