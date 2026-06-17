#!/usr/bin/env python3
"""Parse one knowledge-base document into markdown-oriented text.

The Java service calls this script as an optional structured parser. It prints
one JSON object to stdout and writes diagnostics to stderr.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable


MIN_CONTENT_LENGTH = 40


@dataclass
class ParseResult:
    parser: str
    content: str
    metadata: dict[str, Any]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("path")
    args = parser.parse_args()

    path = Path(args.path)
    if not path.exists():
        return emit_error("file_not_found", f"Input file does not exist: {path}")

    try:
        result = parse_document(path)
        emit_success(result)
        return 0
    except Exception as exc:  # noqa: BLE001 - JSON error payload is the contract.
        return emit_error(type(exc).__name__, str(exc))


def parse_document(path: Path) -> ParseResult:
    suffix = path.suffix.lower()
    if suffix == ".pdf":
        return parse_pdf(path)
    return parse_with_markitdown(path, strategy="markitdown_entry_adapter")


def parse_pdf(path: Path) -> ParseResult:
    complexity = detect_pdf_complexity(path)
    warnings: list[str] = []

    primary = try_parser(lambda: parse_with_pymupdf4llm(path, complexity), warnings)
    if primary and enough_content(primary.content):
        if complexity.get("complex") and complexity.get("table_count", 0) > 0:
            fallback = try_parser(lambda: parse_with_docling(path, complexity, "docling_complex_pdf_fallback"), warnings)
            if fallback and should_prefer_docling(primary.content, fallback.content):
                fallback.metadata["warnings"] = warnings
                return fallback
        primary.metadata["warnings"] = warnings
        return primary

    fallback = try_parser(lambda: parse_with_docling(path, complexity, "docling_pdf_fallback"), warnings)
    if fallback and enough_content(fallback.content):
        fallback.metadata["warnings"] = warnings
        return fallback

    adapter = try_parser(lambda: parse_with_markitdown(path, strategy="markitdown_pdf_last_resort"), warnings)
    if adapter and enough_content(adapter.content):
        adapter.metadata["warnings"] = warnings
        return adapter

    detail = "; ".join(warnings) if warnings else "no parser produced content"
    raise RuntimeError(detail)


def parse_with_pymupdf4llm(path: Path, complexity: dict[str, Any]) -> ParseResult:
    import pymupdf4llm

    content = pymupdf4llm.to_markdown(str(path))
    return ParseResult(
        parser="pymupdf4llm",
        content=normalize_text(content),
        metadata={
            "parserStrategy": "pymupdf4llm_pdf_primary",
            "structured": True,
            "pdfComplexity": complexity,
        },
    )


def parse_with_docling(path: Path, complexity: dict[str, Any], strategy: str) -> ParseResult:
    from docling.document_converter import DocumentConverter

    converted = DocumentConverter().convert(str(path))
    content = converted.document.export_to_markdown()
    return ParseResult(
        parser="docling",
        content=normalize_text(content),
        metadata={
            "parserStrategy": strategy,
            "structured": True,
            "pdfComplexity": complexity,
        },
    )


def parse_with_markitdown(path: Path, strategy: str) -> ParseResult:
    from markitdown import MarkItDown

    result = MarkItDown().convert(str(path))
    content = getattr(result, "text_content", None) or str(result)
    return ParseResult(
        parser="markitdown",
        content=normalize_text(content),
        metadata={
            "parserStrategy": strategy,
            "structured": True,
        },
    )


def detect_pdf_complexity(path: Path) -> dict[str, Any]:
    complexity: dict[str, Any] = {
        "complex": False,
        "table_count": 0,
        "sampled_pages": 0,
        "detector": "pdfplumber",
    }
    try:
        import pdfplumber

        with pdfplumber.open(str(path)) as pdf:
            pages = pdf.pages[: min(5, len(pdf.pages))]
            complexity["sampled_pages"] = len(pages)
            for page in pages:
                tables = page.extract_tables() or []
                complexity["table_count"] += len(tables)
        complexity["complex"] = complexity["table_count"] > 0
    except Exception as exc:  # noqa: BLE001 - optional detector.
        complexity["detectorError"] = f"{type(exc).__name__}: {exc}"
        complexity["detector"] = "unavailable"
    return complexity


def try_parser(factory: Callable[[], ParseResult], warnings: list[str]) -> ParseResult | None:
    try:
        result = factory()
        if result.content:
            return result
        warnings.append(f"{result.parser}: empty content")
    except Exception as exc:  # noqa: BLE001 - parser fallback chain.
        warnings.append(f"{type(exc).__name__}: {exc}")
    return None


def enough_content(content: str) -> bool:
    return bool(content and len(content.strip()) >= MIN_CONTENT_LENGTH)


def should_prefer_docling(primary: str, fallback: str) -> bool:
    if not enough_content(fallback):
        return False
    if not enough_content(primary):
        return True
    primary_tables = primary.count("|")
    fallback_tables = fallback.count("|")
    length_ratio = len(fallback) / max(len(primary), 1)
    return fallback_tables > primary_tables or length_ratio >= 0.8


def normalize_text(content: Any) -> str:
    text = str(content or "").replace("\r\n", "\n").replace("\r", "\n")
    lines = [line.rstrip() for line in text.split("\n")]
    return "\n".join(lines).strip()


def emit_success(result: ParseResult) -> None:
    metadata = dict(result.metadata)
    metadata["parser"] = result.parser
    payload = {
        "success": True,
        "parser": result.parser,
        "content": result.content,
        "metadata": metadata,
    }
    print(json.dumps(payload, ensure_ascii=False))


def emit_error(code: str, message: str) -> int:
    payload = {
        "success": False,
        "errorCode": code,
        "errorMessage": message,
    }
    print(json.dumps(payload, ensure_ascii=False))
    print(f"{code}: {message}", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
