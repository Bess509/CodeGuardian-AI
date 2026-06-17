# RAG PDF Parsing And Chunking Evaluation

This folder contains an offline, repeatable evaluation for comparing the current RAG ingestion strategy against PDF-specialized parsing plus structure-aware chunking.

Compared pipelines:

- `baseline_tika_token`: Spring AI `TikaDocumentReader` plus `TokenTextSplitter` defaults. This mirrors the current Java pipeline.
- `markitdown_structured`: MarkItDown PDF-to-Markdown conversion plus heading/rule-aware chunking with overlap.
- `pymupdf4llm_structured`: PyMuPDF4LLM Markdown extraction plus heading/rule-aware chunking with overlap.
- `docling_structured`: Docling Markdown extraction plus heading/rule-aware chunking with overlap.

Primary metrics:

- `Recall@5`: whether the expected rule appears in the top 5 retrieved chunks.
- `MRR@5`: how high the first expected rule is ranked.
- `Precision@5`: fraction of top 5 chunks that are expected.
- `Semantic loss rate`: how often a rule's key semantic anchors are not preserved together in any chunk.
- `Clean false-positive support rate`: for safe code samples, whether retrieval supports a risky rule without retrieving the expected safe guidance.

Run:

```powershell
python tools\rag_eval\run_rag_eval.py --repo-root .
```

Outputs are written to `tools/rag_eval/runs/latest/`, including generated PDFs, chunks, metrics JSON, and a Markdown report.
