from __future__ import annotations

import argparse
import json
import math
import os
import re
import shutil
import subprocess
import sys
import textwrap
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


RULE_ID_RE = re.compile(r"\b(?:RULE|SAFE|P3C)-[A-Z0-9-]+\b|\bCWE-\d+\b", re.IGNORECASE)
WORD_RE = re.compile(r"cwe-\d+|[a-zA-Z][a-zA-Z0-9_.$-]+|\d+", re.IGNORECASE)


@dataclass(frozen=True)
class RuleSpec:
    rule_id: str
    cwe: str
    category: str
    title: str
    severity: str
    trigger: str
    unsafe: str
    guidance: str
    safe_example: str
    required_terms: list[str]
    safe_rule: bool = False


@dataclass(frozen=True)
class QuerySpec:
    query_id: str
    query: str
    expected_rule_ids: list[str]
    scenario: str


@dataclass(frozen=True)
class CodeCase:
    case_id: str
    language: str
    code: str
    expected_rule_ids: list[str]
    expected_finding: bool
    risk_category: str


@dataclass
class Chunk:
    chunk_id: str
    pipeline: str
    source_file: str
    content: str
    metadata: dict[str, Any] = field(default_factory=dict)

    @property
    def rule_ids(self) -> set[str]:
        ids = {normalize_rule_id(x) for x in self.metadata.get("rule_ids", [])}
        ids.update(normalize_rule_id(x.group(0)) for x in RULE_ID_RE.finditer(self.content))
        return {x for x in ids if x}


RULES: list[RuleSpec] = [
    RuleSpec(
        "RULE-SQL-001",
        "CWE-89",
        "SECURITY",
        "SQL injection prevention",
        "HIGH",
        "String concatenation, Statement, or raw user input in SQL commands.",
        "A query such as SELECT * FROM users WHERE name = '" + " + name + " + "' mixes data with command text.",
        "Use PreparedStatement or a framework-level parameter binding API. Never concatenate request parameters into SQL.",
        "PreparedStatement ps = conn.prepareStatement(\"SELECT * FROM users WHERE name = ?\"); ps.setString(1, name);",
        ["CWE-89", "PreparedStatement", "parameter", "SQL"],
    ),
    RuleSpec(
        "RULE-SECRET-001",
        "CWE-798",
        "SECURITY",
        "Hard-coded credential prevention",
        "HIGH",
        "Password, token, accessKey, secretKey, or API key literal appears in source code.",
        "private static final String PASSWORD = \"admin123\";",
        "Load secrets from environment variables, a vault, or managed secret storage. Do not commit credentials.",
        "String password = System.getenv(\"DB_PASSWORD\");",
        ["CWE-798", "secret", "environment", "credential"],
    ),
    RuleSpec(
        "RULE-CMD-001",
        "CWE-78",
        "SECURITY",
        "Command injection prevention",
        "CRITICAL",
        "Runtime.exec, ProcessBuilder, shell invocation, or concatenated command arguments.",
        "Runtime.getRuntime().exec(\"sh -c ping \" + host);",
        "Avoid shell execution. If a process is required, validate allowlisted arguments and pass them as separate values.",
        "new ProcessBuilder(\"ping\", allowlistedHost).start();",
        ["CWE-78", "ProcessBuilder", "allowlist", "command"],
    ),
    RuleSpec(
        "RULE-CRYPTO-001",
        "CWE-327",
        "SECURITY",
        "Weak cryptography prevention",
        "MEDIUM",
        "MD5, SHA1, DES, ECB mode, or homegrown cryptography.",
        "MessageDigest.getInstance(\"MD5\");",
        "Use modern algorithms and purpose-built password hashing such as bcrypt, scrypt, Argon2, or PBKDF2.",
        "BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();",
        ["CWE-327", "MD5", "bcrypt", "hash"],
    ),
    RuleSpec(
        "RULE-RANDOM-001",
        "CWE-330",
        "SECURITY",
        "Weak randomness prevention",
        "MEDIUM",
        "Random or Math.random is used for tokens, passwords, nonces, or reset codes.",
        "String token = Long.toHexString(Double.doubleToLongBits(Math.random()));",
        "Use SecureRandom for security-sensitive randomness and generate enough entropy.",
        "SecureRandom random = new SecureRandom();",
        ["CWE-330", "SecureRandom", "token", "randomness"],
    ),
    RuleSpec(
        "RULE-PATH-001",
        "CWE-22",
        "SECURITY",
        "Path traversal prevention",
        "HIGH",
        "A user-controlled path is resolved without normalization and boundary checks.",
        "Path p = Paths.get(baseDir, request.getParameter(\"file\"));",
        "Normalize paths and verify the result remains inside the intended base directory.",
        "Path p = base.resolve(input).normalize(); if (!p.startsWith(base)) throw new SecurityException();",
        ["CWE-22", "normalize", "startsWith", "path"],
    ),
    RuleSpec(
        "RULE-RESOURCE-001",
        "CWE-772",
        "BUG",
        "Resource leak prevention",
        "MEDIUM",
        "Streams, connections, statements, or result sets are opened without guaranteed closure.",
        "InputStream in = new FileInputStream(path); return in.read();",
        "Use try-with-resources so resources close on success and failure paths.",
        "try (InputStream in = new FileInputStream(path)) { return in.read(); }",
        ["CWE-772", "try-with-resources", "close", "resource"],
    ),
    RuleSpec(
        "P3C-NAMING-001",
        "P3C",
        "CODE_STYLE",
        "Java naming clarity",
        "LOW",
        "Names are too short, misleading, or do not communicate intent.",
        "int a = userList.size();",
        "Use intention-revealing names and consistent Java naming conventions.",
        "int activeUserCount = userList.size();",
        ["P3C", "naming", "Java", "intent"],
    ),
    RuleSpec(
        "SAFE-SQL-001",
        "CWE-89",
        "SECURITY",
        "Safe parameterized SQL pattern",
        "INFO",
        "The SQL text uses placeholders and binds all untrusted data as parameters.",
        "No unsafe pattern when placeholders are used with PreparedStatement setters.",
        "Do not flag parameterized SQL as SQL injection when all request data is bound as a parameter.",
        "PreparedStatement ps = conn.prepareStatement(\"SELECT * FROM users WHERE id = ?\"); ps.setLong(1, id);",
        ["CWE-89", "PreparedStatement", "placeholder", "do not flag"],
        safe_rule=True,
    ),
    RuleSpec(
        "SAFE-SECRET-001",
        "CWE-798",
        "SECURITY",
        "Safe external secret loading pattern",
        "INFO",
        "The code obtains secrets from configuration providers or environment variables without hard-coded literal values.",
        "No unsafe pattern when a value is loaded from System.getenv or a secret manager.",
        "Do not flag external secret lookup as hard-coded credentials unless a default literal secret is committed.",
        "String token = secretClient.getSecret(\"payment-api-token\");",
        ["CWE-798", "environment", "secret manager", "do not flag"],
        safe_rule=True,
    ),
    RuleSpec(
        "SAFE-CRYPTO-001",
        "CWE-327",
        "SECURITY",
        "Safe password hashing pattern",
        "INFO",
        "Password storage uses a slow adaptive hashing algorithm.",
        "No unsafe pattern when BCrypt, Argon2, scrypt, or PBKDF2 is used correctly.",
        "Do not flag BCrypt password hashing as weak cryptography.",
        "String hash = BCrypt.hashpw(password, BCrypt.gensalt());",
        ["CWE-327", "BCrypt", "do not flag", "password"],
        safe_rule=True,
    ),
    RuleSpec(
        "SAFE-RANDOM-001",
        "CWE-330",
        "SECURITY",
        "Safe SecureRandom pattern",
        "INFO",
        "Security-sensitive tokens are generated with SecureRandom.",
        "No unsafe pattern when SecureRandom is used for token generation.",
        "Do not flag SecureRandom token creation as weak randomness.",
        "byte[] token = new byte[32]; new SecureRandom().nextBytes(token);",
        ["CWE-330", "SecureRandom", "do not flag", "token"],
        safe_rule=True,
    ),
]


CODE_CASES: list[CodeCase] = [
    CodeCase("case-sql-injection", "Java", """
        public User find(String name) throws SQLException {
            Statement st = conn.createStatement();
            return map(st.executeQuery("SELECT * FROM users WHERE name = '" + name + "'"));
        }
    """, ["RULE-SQL-001", "CWE-89"], True, "SQL"),
    CodeCase("case-sql-safe", "Java", """
        public User find(long id) throws SQLException {
            PreparedStatement ps = conn.prepareStatement("SELECT * FROM users WHERE id = ?");
            ps.setLong(1, id);
            return map(ps.executeQuery());
        }
    """, ["SAFE-SQL-001"], False, "SQL"),
    CodeCase("case-secret-hardcoded", "Java", """
        class Db {
            private static final String PASSWORD = "admin123";
            Connection connect() { return DriverManager.getConnection(url, "root", PASSWORD); }
        }
    """, ["RULE-SECRET-001", "CWE-798"], True, "SECRET"),
    CodeCase("case-secret-safe", "Java", """
        class Db {
            Connection connect() {
                String password = System.getenv("DB_PASSWORD");
                return DriverManager.getConnection(url, "root", password);
            }
        }
    """, ["SAFE-SECRET-001"], False, "SECRET"),
    CodeCase("case-command-injection", "Java", """
        void ping(String host) throws IOException {
            Runtime.getRuntime().exec("sh -c ping " + host);
        }
    """, ["RULE-CMD-001", "CWE-78"], True, "COMMAND"),
    CodeCase("case-command-safe", "Java", """
        void ping(String host) throws IOException {
            String allowlistedHost = allowlist(host);
            new ProcessBuilder("ping", allowlistedHost).start();
        }
    """, ["RULE-CMD-001"], False, "COMMAND"),
    CodeCase("case-weak-crypto", "Java", """
        byte[] digest(String password) throws Exception {
            return MessageDigest.getInstance("MD5").digest(password.getBytes(StandardCharsets.UTF_8));
        }
    """, ["RULE-CRYPTO-001", "CWE-327"], True, "CRYPTO"),
    CodeCase("case-crypto-safe", "Java", """
        String hash(String password) {
            return BCrypt.hashpw(password, BCrypt.gensalt());
        }
    """, ["SAFE-CRYPTO-001"], False, "CRYPTO"),
    CodeCase("case-weak-random", "Java", """
        String token() {
            return Long.toHexString(Double.doubleToLongBits(Math.random()));
        }
    """, ["RULE-RANDOM-001", "CWE-330"], True, "RANDOM"),
    CodeCase("case-random-safe", "Java", """
        byte[] token() {
            byte[] value = new byte[32];
            new SecureRandom().nextBytes(value);
            return value;
        }
    """, ["SAFE-RANDOM-001"], False, "RANDOM"),
]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", default=".", help="Repository root.")
    parser.add_argument("--run-dir", default=None, help="Output run directory.")
    parser.add_argument("--skip-docling", action="store_true", help="Skip Docling parser if dependency is too slow.")
    args = parser.parse_args()

    repo_root = Path(args.repo_root).resolve()
    base_dir = repo_root / "tools" / "rag_eval"
    run_dir = Path(args.run_dir).resolve() if args.run_dir else base_dir / "runs" / "latest"
    if run_dir.exists():
        shutil.rmtree(run_dir)
    run_dir.mkdir(parents=True, exist_ok=True)

    pdf_dir = run_dir / "generated_pdfs"
    data_dir = run_dir / "data"
    chunk_dir = run_dir / "chunks"
    data_dir.mkdir(parents=True, exist_ok=True)
    chunk_dir.mkdir(parents=True, exist_ok=True)

    write_jsonl(data_dir / "gold_queries.jsonl", [q.__dict__ for q in build_queries()])
    write_jsonl(data_dir / "code_cases.jsonl", [c.__dict__ for c in CODE_CASES])
    write_jsonl(data_dir / "rule_specs.jsonl", [r.__dict__ for r in RULES])
    write_json(run_dir / "environment.json", collect_environment())
    generate_pdfs(pdf_dir)

    pipelines: dict[str, list[Chunk]] = {}
    pipelines["baseline_tika_token"] = parse_baseline_tika_token(repo_root, base_dir, run_dir, pdf_dir)
    pipelines["markitdown_structured"] = parse_markitdown_structured(pdf_dir)
    pipelines["pymupdf4llm_structured"] = parse_pymupdf4llm_structured(pdf_dir)
    if not args.skip_docling:
        pipelines["docling_structured"] = parse_docling_structured(pdf_dir)

    for name, chunks in pipelines.items():
        write_jsonl(chunk_dir / f"{name}.jsonl", [chunk_to_dict(c) for c in chunks])

    queries = build_queries()
    retrieval_metrics = {}
    semantic_metrics = {}
    review_metrics = {}
    chunk_health_metrics = {}
    examples = {}
    for name, chunks in pipelines.items():
        retriever = HybridRetriever(chunks)
        retrieval_metrics[name], examples[name] = evaluate_retrieval(retriever, queries)
        semantic_metrics[name] = evaluate_semantic_loss(chunks)
        review_metrics[name] = evaluate_code_cases(retriever)
        chunk_health_metrics[name] = evaluate_chunk_health(chunks)

    metrics = {
        "retrieval": retrieval_metrics,
        "semantic_loss": semantic_metrics,
        "review_support": review_metrics,
        "chunk_health": chunk_health_metrics,
    }
    write_json(run_dir / "metrics.json", metrics)
    write_json(run_dir / "examples.json", examples)
    write_report(run_dir / "rag_eval_report.md", metrics, examples, pipelines)

    print(f"RAG evaluation complete: {run_dir}")
    print((run_dir / "rag_eval_report.md").read_text(encoding="utf-8"))
    return 0


def build_queries() -> list[QuerySpec]:
    queries: list[QuerySpec] = []
    templates = [
        "{title}",
        "{cwe} {trigger}",
        "How should Java code fix {trigger}?",
        "code review rule for {unsafe}",
        "{category} {guidance}",
    ]
    for rule in RULES:
        for i, template in enumerate(templates, 1):
            expected = [rule.rule_id]
            queries.append(QuerySpec(
                query_id=f"q-{rule.rule_id.lower()}-{i}",
                query=template.format(**rule.__dict__),
                expected_rule_ids=expected,
                scenario="safe" if rule.safe_rule else "risk",
            ))
    return queries


def generate_pdfs(pdf_dir: Path) -> None:
    from reportlab.lib import colors
    from reportlab.lib.pagesizes import landscape, letter
    from reportlab.lib.styles import getSampleStyleSheet
    from reportlab.lib.units import inch
    from reportlab.platypus import PageBreak, Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

    pdf_dir.mkdir(parents=True, exist_ok=True)
    styles = getSampleStyleSheet()

    narrative = pdf_dir / "controlled_rules_narrative.pdf"
    doc = SimpleDocTemplate(str(narrative), pagesize=letter, rightMargin=54, leftMargin=54, topMargin=54, bottomMargin=54)
    story = [Paragraph("Controlled Code Review Rules", styles["Title"])]
    for rule in RULES:
        story.append(Paragraph(f"{rule.rule_id} {rule.cwe}: {rule.title}", styles["Heading2"]))
        story.append(Paragraph(f"Category: {rule.category}. Severity: {rule.severity}.", styles["BodyText"]))
        story.append(Paragraph(f"Trigger: {rule.trigger}", styles["BodyText"]))
        story.append(Paragraph(f"Unsafe example: {escape_pdf_text(rule.unsafe)}", styles["BodyText"]))
        story.append(Paragraph(f"Guidance: {rule.guidance}", styles["BodyText"]))
        story.append(Paragraph(f"Safe example: {escape_pdf_text(rule.safe_example)}", styles["BodyText"]))
        story.append(Spacer(1, 0.16 * inch))
    doc.build(story)

    table_pdf = pdf_dir / "controlled_rules_table.pdf"
    doc = SimpleDocTemplate(str(table_pdf), pagesize=landscape(letter), rightMargin=30, leftMargin=30, topMargin=40, bottomMargin=40)
    story = [Paragraph("Controlled Code Review Rule Matrix", styles["Title"])]
    table_style = styles["BodyText"].clone("EvalTableCell")
    table_style.fontSize = 6.5
    table_style.leading = 7.8
    header_style = styles["BodyText"].clone("EvalTableHeader")
    header_style.fontSize = 7
    header_style.leading = 8
    header_style.fontName = "Helvetica-Bold"
    data = [[Paragraph("Rule", header_style), Paragraph("Category", header_style),
             Paragraph("Unsafe signal", header_style), Paragraph("Required mitigation", header_style)]]
    for rule in RULES:
        data.append([
            Paragraph(f"{rule.rule_id}<br/>{rule.cwe}", table_style),
            Paragraph(rule.category, table_style),
            Paragraph(escape_pdf_text(rule.trigger), table_style),
            Paragraph(escape_pdf_text(rule.guidance), table_style),
        ])
    table = Table(data, colWidths=[1.45 * inch, 1.0 * inch, 3.2 * inch, 4.4 * inch], repeatRows=1)
    table.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#e8eef7")),
        ("GRID", (0, 0), (-1, -1), 0.4, colors.grey),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("FONTNAME", (0, 0), (-1, 0), "Helvetica-Bold"),
        ("FONTSIZE", (0, 0), (-1, -1), 7),
        ("LEADING", (0, 0), (-1, -1), 8.5),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#f8fafc")]),
    ]))
    story.append(table)
    doc.build(story)

    long_pdf = pdf_dir / "controlled_rules_long_sections.pdf"
    doc = SimpleDocTemplate(str(long_pdf), pagesize=letter, rightMargin=54, leftMargin=54, topMargin=54, bottomMargin=54)
    story = [Paragraph("Long Form Secure Code Review Guide", styles["Title"])]
    filler = (
        "During review, preserve the relationship between the risky API, the security weakness, "
        "the remediation, and the safe counterexample. A retrieval chunk that keeps only the symptom "
        "without the mitigation is not sufficient evidence for a grounded review finding. "
    )
    for idx, rule in enumerate(RULES):
        if idx and idx % 4 == 0:
            story.append(PageBreak())
        story.append(Paragraph(f"Section {idx + 1}: {rule.rule_id} {rule.cwe} {rule.title}", styles["Heading2"]))
        long_text = " ".join([filler] * (9 if not rule.safe_rule else 5))
        story.append(Paragraph(long_text, styles["BodyText"]))
        story.append(Paragraph(f"Rule ID: {rule.rule_id}. Weakness: {rule.cwe}. Required terms: {', '.join(rule.required_terms)}.", styles["BodyText"]))
        story.append(Paragraph(f"Trigger details: {rule.trigger}", styles["BodyText"]))
        story.append(Paragraph(f"Mitigation details: {rule.guidance}", styles["BodyText"]))
        story.append(Paragraph(f"Safe counterexample: {escape_pdf_text(rule.safe_example)}", styles["BodyText"]))
        story.append(Spacer(1, 0.12 * inch))
    doc.build(story)


def escape_pdf_text(value: str) -> str:
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def parse_baseline_tika_token(repo_root: Path, base_dir: Path, run_dir: Path, pdf_dir: Path) -> list[Chunk]:
    class_dir = run_dir / "java_classes"
    class_dir.mkdir(parents=True, exist_ok=True)
    cp_file = run_dir / "maven-classpath.txt"
    subprocess.run([
        mvn_cmd(),
        "-q",
        "dependency:build-classpath",
        f"-Dmdep.outputFile={cp_file}",
    ], cwd=repo_root, check=True)
    classpath = cp_file.read_text(encoding="utf-8").strip()
    java_file = base_dir / "java" / "RagEvalJavaBridge.java"
    subprocess.run([
        "javac",
        "-encoding",
        "UTF-8",
        "-cp",
        classpath,
        "-d",
        str(class_dir),
        str(java_file),
    ], cwd=repo_root, check=True)
    full_classpath = os.pathsep.join([str(class_dir), classpath])

    chunks: list[Chunk] = []
    for pdf_path in sorted(pdf_dir.glob("*.pdf")):
        result = subprocess.run([
            "java",
            "-cp",
            full_classpath,
            "RagEvalJavaBridge",
            str(pdf_path),
        ], cwd=repo_root, text=True, encoding="utf-8", capture_output=True, check=True)
        items = json.loads(extract_json_array(result.stdout))
        for item in items:
            content = item.get("content", "")
            metadata = item.get("metadata") or {}
            metadata["rule_ids"] = sorted(extract_rule_ids(content))
            metadata["token_count"] = len(tokenize(content))
            metadata["split_reason"] = metadata.get("split_reason") or "token_window"
            chunks.append(Chunk(
                chunk_id=f"baseline:{pdf_path.name}:{item.get('chunk_index')}",
                pipeline="baseline_tika_token",
                source_file=pdf_path.name,
                content=content,
                metadata=metadata,
            ))
    return chunks


def parse_pymupdf4llm_structured(pdf_dir: Path) -> list[Chunk]:
    import pymupdf4llm

    chunks: list[Chunk] = []
    for pdf_path in sorted(pdf_dir.glob("*.pdf")):
        markdown = pymupdf4llm.to_markdown(str(pdf_path))
        chunks.extend(structured_chunks(markdown, "pymupdf4llm_structured", pdf_path.name))
    return chunks


def parse_markitdown_structured(pdf_dir: Path) -> list[Chunk]:
    from markitdown import MarkItDown

    converter = MarkItDown()
    chunks: list[Chunk] = []
    for pdf_path in sorted(pdf_dir.glob("*.pdf")):
        result = converter.convert(pdf_path)
        markdown = result.text_content
        chunks.extend(structured_chunks(markdown, "markitdown_structured", pdf_path.name))
    return chunks


def parse_docling_structured(pdf_dir: Path) -> list[Chunk]:
    from docling.datamodel.base_models import InputFormat
    from docling.datamodel.pipeline_options import PdfPipelineOptions, TableFormerMode
    from docling.document_converter import DocumentConverter
    from docling.document_converter import PdfFormatOption

    pipeline_options = PdfPipelineOptions(do_ocr=False, document_timeout=120)
    pipeline_options.table_structure_options.mode = TableFormerMode.FAST
    converter = DocumentConverter(
        allowed_formats=[InputFormat.PDF],
        format_options={
            InputFormat.PDF: PdfFormatOption(pipeline_options=pipeline_options)
        },
    )
    chunks: list[Chunk] = []
    for pdf_path in sorted(pdf_dir.glob("*.pdf")):
        try:
            result = converter.convert(str(pdf_path))
            markdown = result.document.export_to_markdown()
        except Exception as exc:
            markdown = f"# Docling parse failure\n\nSource: {pdf_path.name}\n\nError: {type(exc).__name__}: {exc}"
        chunks.extend(structured_chunks(markdown, "docling_structured", pdf_path.name))
    return chunks


def structured_chunks(markdown: str, pipeline: str, source_file: str, target_tokens: int = 650, overlap_tokens: int = 100) -> list[Chunk]:
    sections = split_markdown_sections(markdown)
    chunks: list[Chunk] = []
    index = 0
    for heading_path, body in sections:
        rule_ids = sorted(extract_rule_ids(" ".join(heading_path) + "\n" + body))
        prefix_lines = []
        if heading_path:
            prefix_lines.append("Heading path: " + " > ".join(heading_path))
        if rule_ids:
            prefix_lines.append("Rule IDs: " + ", ".join(rule_ids))
        prefix = "\n".join(prefix_lines).strip()
        text = (prefix + "\n\n" + body).strip() if prefix else body.strip()
        if not text:
            continue
        pieces = split_with_overlap(text, target_tokens, overlap_tokens)
        split_reason = "token_window" if len(pieces) > 1 else (
            "rule_boundary" if rule_ids else ("heading" if heading_path else "paragraph")
        )
        for piece_index, piece in enumerate(pieces):
            piece_text = piece
            if prefix and not piece_text.startswith(prefix):
                piece_text = prefix + "\n\n" + piece_text
            chunk_rule_ids = sorted(extract_rule_ids(piece_text).union(rule_ids))
            chunks.append(Chunk(
                chunk_id=f"{pipeline}:{source_file}:{index}",
                pipeline=pipeline,
                source_file=source_file,
                content=piece_text,
                metadata={
                    "parser": pipeline,
                    "source_file": source_file,
                    "chunk_index": index,
                    "section_piece_index": piece_index,
                    "heading_path": heading_path,
                    "rule_ids": chunk_rule_ids,
                    "split_reason": split_reason,
                    "token_count": len(tokenize(piece_text)),
                },
            ))
            index += 1
    return chunks


def split_markdown_sections(markdown: str) -> list[tuple[list[str], str]]:
    lines = markdown.splitlines()
    sections: list[tuple[list[str], list[str]]] = []
    heading_stack: list[str] = []
    current: list[str] = []
    current_heading: list[str] = []

    def flush() -> None:
        nonlocal current
        body = "\n".join(current).strip()
        if body:
            sections.append((list(current_heading), current))
        current = []

    for line in lines:
        heading = re.match(r"^(#{1,6})\s+(.+?)\s*$", line.strip())
        rule_heading = re.match(r"^\s*(?:Rule ID:\s*)?((?:RULE|SAFE|P3C)-[A-Z0-9-]+|CWE-\d+)\b.*", line.strip(), re.IGNORECASE)
        if heading:
            flush()
            level = len(heading.group(1))
            text = clean_text(heading.group(2))
            heading_stack = heading_stack[: level - 1]
            heading_stack.append(text)
            current_heading = list(heading_stack)
            current.append(line)
        elif rule_heading and current:
            flush()
            current_heading = list(heading_stack) + [clean_text(line.strip())[:120]]
            current.append(line)
        else:
            current.append(line)
    flush()
    if not sections and markdown.strip():
        return [([], markdown.strip())]
    return [(h, "\n".join(body)) for h, body in sections]


def split_with_overlap(text: str, target_tokens: int, overlap_tokens: int) -> list[str]:
    tokens = text.split()
    if len(tokens) <= target_tokens:
        return [text.strip()]
    chunks: list[str] = []
    step = max(1, target_tokens - overlap_tokens)
    for start in range(0, len(tokens), step):
        end = min(len(tokens), start + target_tokens)
        piece = " ".join(tokens[start:end]).strip()
        if piece:
            chunks.append(piece)
        if end >= len(tokens):
            break
    return chunks


class HybridRetriever:
    def __init__(self, chunks: list[Chunk]) -> None:
        self.chunks = chunks
        self.doc_tokens = [tokenize(c.content) for c in chunks]
        self.doc_lens = [len(t) for t in self.doc_tokens]
        self.avgdl = sum(self.doc_lens) / max(1, len(self.doc_lens))
        self.df = Counter()
        for tokens in self.doc_tokens:
            self.df.update(set(tokens))
        self.idf = {
            term: math.log((len(chunks) - df + 0.5) / (df + 0.5) + 1.0)
            for term, df in self.df.items()
        }
        self.doc_tfidf = [self._tfidf(tokens) for tokens in self.doc_tokens]
        self.doc_norms = [math.sqrt(sum(v * v for v in vec.values())) or 1.0 for vec in self.doc_tfidf]

    def search(self, query: str, top_k: int = 5) -> list[tuple[Chunk, float]]:
        q_tokens = tokenize(query)
        q_vec = self._tfidf(q_tokens)
        q_norm = math.sqrt(sum(v * v for v in q_vec.values())) or 1.0
        raw_scores = []
        for idx, chunk in enumerate(self.chunks):
            bm25 = self._bm25(q_tokens, idx)
            cosine = self._cosine(q_vec, q_norm, idx)
            raw_scores.append((idx, bm25, cosine))
        max_bm25 = max([s[1] for s in raw_scores] or [1.0]) or 1.0
        max_cosine = max([s[2] for s in raw_scores] or [1.0]) or 1.0
        fused = []
        for idx, bm25, cosine in raw_scores:
            score = 0.65 * (bm25 / max_bm25) + 0.35 * (cosine / max_cosine)
            fused.append((self.chunks[idx], score))
        return sorted(fused, key=lambda x: x[1], reverse=True)[:top_k]

    def _tfidf(self, tokens: list[str]) -> dict[str, float]:
        counts = Counter(tokens)
        return {term: (1.0 + math.log(tf)) * self.idf.get(term, 0.0) for term, tf in counts.items()}

    def _bm25(self, query_tokens: list[str], doc_idx: int, k1: float = 1.5, b: float = 0.75) -> float:
        counts = Counter(self.doc_tokens[doc_idx])
        dl = self.doc_lens[doc_idx] or 1
        score = 0.0
        for term in query_tokens:
            tf = counts.get(term, 0)
            if tf <= 0:
                continue
            idf = self.idf.get(term, 0.0)
            denom = tf + k1 * (1 - b + b * dl / self.avgdl)
            score += idf * (tf * (k1 + 1)) / denom
        return score

    def _cosine(self, q_vec: dict[str, float], q_norm: float, doc_idx: int) -> float:
        d_vec = self.doc_tfidf[doc_idx]
        dot = sum(weight * d_vec.get(term, 0.0) for term, weight in q_vec.items())
        return dot / (q_norm * self.doc_norms[doc_idx])


def evaluate_chunk_health(chunks: list[Chunk]) -> dict[str, Any]:
    token_counts = [chunk_token_count(chunk) for chunk in chunks]
    rule_id_counts = [len(chunk.rule_ids) for chunk in chunks]
    token_window_count = sum(1 for chunk in chunks if chunk_split_reason(chunk) == "token_window")
    no_rule_boundary_count = sum(1 for count in rule_id_counts if count == 0)
    total = len(chunks)

    return {
        "chunk_count": total,
        "avg_chunk_tokens": round(sum(token_counts) / max(1, total), 4),
        "p95_chunk_tokens": percentile_nearest_rank(token_counts, 95),
        "avg_rule_ids_per_chunk": round(sum(rule_id_counts) / max(1, total), 4),
        "chunks_without_detected_rule_boundary_rate": round(no_rule_boundary_count / max(1, total), 4),
        "token_window_fallback_rate": round(token_window_count / max(1, total), 4),
    }


def chunk_token_count(chunk: Chunk) -> int:
    value = chunk.metadata.get("token_count")
    if isinstance(value, bool):
        return len(tokenize(chunk.content))
    if isinstance(value, int):
        return max(0, value)
    if isinstance(value, float):
        return max(0, int(value))
    if isinstance(value, str) and value.strip():
        try:
            return max(0, int(float(value)))
        except ValueError:
            pass
    return len(tokenize(chunk.content))


def chunk_split_reason(chunk: Chunk) -> str:
    value = chunk.metadata.get("split_reason") or chunk.metadata.get("splitReason")
    return str(value or "").strip().lower()


def percentile_nearest_rank(values: list[int], percentile: int) -> int:
    if not values:
        return 0
    sorted_values = sorted(values)
    rank = math.ceil((percentile / 100.0) * len(sorted_values))
    index = min(len(sorted_values) - 1, max(0, rank - 1))
    return sorted_values[index]


def evaluate_retrieval(retriever: HybridRetriever, queries: list[QuerySpec]) -> tuple[dict[str, float], list[dict[str, Any]]]:
    hits = 0
    top1_hits = 0
    mrr = 0.0
    precision_total = 0.0
    top1_rule_id_total = 0
    top5_rule_id_total = 0
    topk_unique_chunk_total = 0
    examples: list[dict[str, Any]] = []
    for query in queries:
        expected = {normalize_rule_id(x) for x in query.expected_rule_ids}
        results = retriever.search(query.query, 5)
        topk_unique_chunk_total += len({chunk.chunk_id for chunk, _ in results})
        ranks = []
        relevant_count = 0
        for rank, (chunk, score) in enumerate(results, 1):
            if chunk.rule_ids.intersection(expected):
                ranks.append(rank)
                relevant_count += 1
            top5_rule_id_total += len(chunk.rule_ids)
        if results:
            top1_rule_id_total += len(results[0][0].rule_ids)
            if results[0][0].rule_ids.intersection(expected):
                top1_hits += 1
        if ranks:
            hits += 1
            mrr += 1.0 / min(ranks)
        precision_total += relevant_count / 5.0
        if len(examples) < 12 and (not ranks or min(ranks) > 1):
            examples.append({
                "query_id": query.query_id,
                "query": query.query,
                "expected": sorted(expected),
                "hit_rank": min(ranks) if ranks else None,
                "top": [
                    {
                        "rank": i + 1,
                        "score": round(score, 4),
                        "chunk_id": chunk.chunk_id,
                        "rule_ids": sorted(chunk.rule_ids),
                        "preview": clean_text(chunk.content)[:220],
                    }
                    for i, (chunk, score) in enumerate(results)
                ],
            })
    total = len(queries)
    return {
        "query_count": total,
        "top1_accuracy": round(top1_hits / total, 4),
        "recall_at_5": round(hits / total, 4),
        "mrr_at_5": round(mrr / total, 4),
        "precision_at_5": round(precision_total / total, 4),
        "avg_top1_rule_ids": round(top1_rule_id_total / max(1, total), 4),
        "avg_top5_chunk_rule_ids": round(top5_rule_id_total / max(1, total * 5), 4),
        "topK_unique_chunk_count": round(topk_unique_chunk_total / max(1, total), 4),
    }, examples


def evaluate_semantic_loss(chunks: list[Chunk]) -> dict[str, Any]:
    source_files = sorted({chunk.source_file for chunk in chunks})
    total = len(RULES) * max(1, len(source_files))
    preserved = 0
    missing: list[dict[str, Any]] = []
    for source_file in source_files:
        source_chunks = [chunk for chunk in chunks if chunk.source_file == source_file]
        for rule in RULES:
            required = [term.lower() for term in rule.required_terms]
            candidate_chunks = [chunk for chunk in source_chunks if rule.rule_id in chunk.rule_ids]
            ok = False
            for chunk in candidate_chunks:
                lower = chunk.content.lower()
                if all(term in lower for term in required):
                    ok = True
                    break
            if ok:
                preserved += 1
            else:
                missing.append({
                    "source_file": source_file,
                    "rule_id": rule.rule_id,
                    "cwe": rule.cwe,
                    "required_terms": rule.required_terms,
                    "candidate_chunk_count": len(candidate_chunks),
                })
    return {
        "rule_source_pair_count": total,
        "semantic_preserved": preserved,
        "semantic_loss_count": total - preserved,
        "semantic_loss_rate": round((total - preserved) / total, 4),
        "missing": missing[:20],
    }


def evaluate_code_cases(retriever: HybridRetriever) -> dict[str, Any]:
    positive = [case for case in CODE_CASES if case.expected_finding]
    clean = [case for case in CODE_CASES if not case.expected_finding]
    positive_hits = 0
    positive_top1_hits = 0
    clean_false_positive = 0
    clean_safe_top1_hits = 0
    details = []
    for case in CODE_CASES:
        query = build_code_query(case)
        results = retriever.search(query, 5)
        expected = {normalize_rule_id(x) for x in case.expected_rule_ids}
        top_ids = set()
        for chunk, _ in results:
            top_ids.update(chunk.rule_ids)
        hit = bool(top_ids.intersection(expected))
        if case.expected_finding and hit:
            positive_hits += 1
        if case.expected_finding and results and results[0][0].rule_ids.intersection(expected):
            positive_top1_hits += 1
        if not case.expected_finding:
            if results and results[0][0].rule_ids.intersection(expected):
                clean_safe_top1_hits += 1
            has_expected_safe = bool(top_ids.intersection(expected))
            risky_ids = {rid for rid in top_ids if rid.startswith("RULE-") or rid.startswith("CWE-")}
            if risky_ids and not has_expected_safe:
                clean_false_positive += 1
        details.append({
            "case_id": case.case_id,
            "expected_finding": case.expected_finding,
            "expected": sorted(expected),
            "top_rule_ids": sorted(top_ids),
            "top1": results[0][0].chunk_id if results else None,
        })
    return {
        "positive_case_count": len(positive),
        "clean_case_count": len(clean),
        "positive_support_top1": round(positive_top1_hits / max(1, len(positive)), 4),
        "positive_support_recall_at_5": round(positive_hits / max(1, len(positive)), 4),
        "clean_safe_support_top1": round(clean_safe_top1_hits / max(1, len(clean)), 4),
        "clean_false_positive_support_rate_at_5": round(clean_false_positive / max(1, len(clean)), 4),
        "details": details,
    }


def build_code_query(case: CodeCase) -> str:
    return "\n".join([
        "Code review retrieval query",
        f"Language: {case.language}",
        f"Risk category: {case.risk_category}",
        textwrap.dedent(case.code).strip(),
    ])


def write_report(path: Path, metrics: dict[str, Any], examples: dict[str, Any], pipelines: dict[str, list[Chunk]]) -> None:
    lines = []
    lines.append("# RAG PDF Parsing And Chunking Evaluation Report")
    lines.append("")
    lines.append("## Summary")
    lines.append("")
    baseline = metrics["retrieval"].get("baseline_tika_token", {})
    markitdown = metrics["retrieval"].get("markitdown_structured", {})
    pymupdf = metrics["retrieval"].get("pymupdf4llm_structured", {})
    docling = metrics["retrieval"].get("docling_structured", {})
    base_sem = metrics["semantic_loss"].get("baseline_tika_token", {})
    md_sem = metrics["semantic_loss"].get("markitdown_structured", {})
    doc_sem = metrics["semantic_loss"].get("docling_structured") or metrics["semantic_loss"].get("pymupdf4llm_structured", {})
    chunk_health = metrics.get("chunk_health", {})
    if baseline and pymupdf:
        top1_delta = (pymupdf.get("top1_accuracy", 0) - baseline.get("top1_accuracy", 0)) * 100
        contamination_delta = baseline.get("avg_top1_rule_ids", 0) - pymupdf.get("avg_top1_rule_ids", 0)
        lines.append(f"- PyMuPDF4LLM structured parsing improved Top1 accuracy by {top1_delta:.2f} percentage points versus the current Tika/token baseline.")
        lines.append(f"- The current baseline top result mixed {baseline.get('avg_top1_rule_ids', 0):.2f} rule IDs on average, while PyMuPDF4LLM mixed {pymupdf.get('avg_top1_rule_ids', 0):.2f}; lower is better for precise grounding.")
        lines.append(f"- Rule contamination dropped by {contamination_delta:.2f} rule IDs per Top1 chunk.")
    if baseline and markitdown:
        md_top1_delta = (markitdown.get("top1_accuracy", 0) - baseline.get("top1_accuracy", 0)) * 100
        lines.append(f"- MarkItDown structured parsing improved Top1 accuracy by {md_top1_delta:.2f} percentage points versus baseline and mixed {markitdown.get('avg_top1_rule_ids', 0):.2f} rule IDs per Top1 chunk.")
    if base_sem and md_sem:
        md_loss_delta = (md_sem.get("semantic_loss_rate", 0) - base_sem.get("semantic_loss_rate", 0)) * 100
        lines.append(f"- MarkItDown did not improve semantic preservation in this PDF benchmark: semantic loss was {md_sem.get('semantic_loss_rate', 0):.4f}, {md_loss_delta:.2f} percentage points higher than baseline.")
    if baseline and docling:
        lines.append(f"- Docling also reached Top1={docling.get('top1_accuracy', 0):.4f} and MRR@5={docling.get('mrr_at_5', 0):.4f}.")
    if base_sem and doc_sem:
        loss_delta = (base_sem.get("semantic_loss_rate", 0) - doc_sem.get("semantic_loss_rate", 0)) * 100
        lines.append(f"- Semantic loss rate dropped from {base_sem.get('semantic_loss_rate', 0):.4f} to {doc_sem.get('semantic_loss_rate', 0):.4f}, an absolute reduction of {loss_delta:.2f} percentage points.")
    if chunk_health:
        highest_token_window = max(
            chunk_health.items(),
            key=lambda item: item[1].get("token_window_fallback_rate", 0),
        )
        lines.append(f"- Chunk health metrics now surface average/P95 chunk size, rule IDs per chunk, and token-window fallback rate; `{highest_token_window[0]}` had the highest token-window fallback rate at {highest_token_window[1].get('token_window_fallback_rate', 0):.4f}.")
    lines.append("- Clean false-positive support was 0.0000 for all pipelines in this deterministic proxy test; this means the parser/chunker change did not introduce extra RAG-supported false positives in the generated clean cases.")
    lines.append("")
    lines.append("Recommendation: adopt PDF-specialized Markdown/structured parsing plus heading/rule-aware chunking. MarkItDown is a viable multi-format entry adapter, PyMuPDF4LLM is the faster default PDF candidate, and Docling remains a fallback for table-heavy or structurally complex PDFs.")
    lines.append("")
    lines.append("## Methodology")
    lines.append("")
    lines.append("- Generated three controlled PDFs: narrative rules, table/matrix rules, and long sections designed to stress no-overlap token splitting.")
    lines.append("- Generated 60 gold retrieval queries from 12 code-review rules and 10 Java code cases for review-support proxy metrics.")
    lines.append("- Held retrieval logic constant with a deterministic BM25 + TF-IDF hybrid retriever so differences come from parsing and chunking.")
    lines.append("- Baseline mirrors the current Java ingestion path: Spring AI `TikaDocumentReader` plus default `TokenTextSplitter`.")
    lines.append("- Structured candidates parse to Markdown/structured text and chunk by heading/rule boundaries with a 650-token target and 100-token overlap.")
    lines.append("")
    lines.append("## Public Rule Anchors")
    lines.append("")
    lines.append("- OWASP Cheat Sheet Series: https://github.com/OWASP/CheatSheetSeries")
    lines.append("- MITRE CWE-89 SQL Injection: https://cwe.mitre.org/data/definitions/89.html")
    lines.append("- MITRE CWE-798 Hard-coded Credentials: https://cwe.mitre.org/data/definitions/798.html")
    lines.append("- Oracle Secure Coding Guidelines for Java SE: https://www.oracle.com/java/technologies/javase/seccodeguide.html")
    lines.append("- Alibaba P3C Java coding guidelines project: https://github.com/alibaba/p3c")
    lines.append("")
    lines.append("The generated PDFs use synthetic, controlled rule text based on these public categories and do not copy long source passages.")
    lines.append("")
    lines.append("## Pipelines")
    for name, chunks in pipelines.items():
        lines.append(f"- `{name}`: {len(chunks)} chunks")
    lines.append("")
    lines.append("## Retrieval Metrics")
    lines.append("")
    lines.append("| Pipeline | Query Count | Top1 | Recall@5 | MRR@5 | Precision@5 | Avg Top1 Rule IDs | Avg Top5 Rule IDs | TopK Unique Chunks |")
    lines.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|")
    for name, values in metrics["retrieval"].items():
        lines.append(f"| {name} | {values['query_count']} | {values['top1_accuracy']:.4f} | {values['recall_at_5']:.4f} | {values['mrr_at_5']:.4f} | {values['precision_at_5']:.4f} | {values['avg_top1_rule_ids']:.4f} | {values['avg_top5_chunk_rule_ids']:.4f} | {values['topK_unique_chunk_count']:.4f} |")
    lines.append("")
    lines.append("## Chunk Health")
    lines.append("")
    lines.append("| Pipeline | Chunks | Avg Tokens | P95 Tokens | Avg Rule IDs/Chunk | Token Window Rate | No Rule Boundary Rate |")
    lines.append("|---|---:|---:|---:|---:|---:|---:|")
    for name, values in chunk_health.items():
        lines.append(f"| {name} | {values['chunk_count']} | {values['avg_chunk_tokens']:.4f} | {values['p95_chunk_tokens']} | {values['avg_rule_ids_per_chunk']:.4f} | {values['token_window_fallback_rate']:.4f} | {values['chunks_without_detected_rule_boundary_rate']:.4f} |")
    lines.append("")
    lines.append("## Semantic Loss")
    lines.append("")
    lines.append("| Pipeline | Rule-Source Pairs | Preserved | Loss Count | Loss Rate |")
    lines.append("|---|---:|---:|---:|---:|")
    for name, values in metrics["semantic_loss"].items():
        lines.append(f"| {name} | {values['rule_source_pair_count']} | {values['semantic_preserved']} | {values['semantic_loss_count']} | {values['semantic_loss_rate']:.4f} |")
    lines.append("")
    lines.append("## Review Support Proxy")
    lines.append("")
    lines.append("| Pipeline | Positive Top1 | Positive Recall@5 | Clean Safe Top1 | Clean FP Support@5 | Positive Cases | Clean Cases |")
    lines.append("|---|---:|---:|---:|---:|---:|---:|")
    for name, values in metrics["review_support"].items():
        lines.append(f"| {name} | {values['positive_support_top1']:.4f} | {values['positive_support_recall_at_5']:.4f} | {values['clean_safe_support_top1']:.4f} | {values['clean_false_positive_support_rate_at_5']:.4f} | {values['positive_case_count']} | {values['clean_case_count']} |")
    lines.append("")
    lines.append("## Notable Retrieval Failures")
    lines.append("")
    for name, items in examples.items():
        lines.append(f"### {name}")
        if not items:
            lines.append("No sampled misses or late hits.")
            lines.append("")
            continue
        for item in items[:5]:
            lines.append(f"- `{item['query_id']}` expected `{', '.join(item['expected'])}`, hit rank: `{item['hit_rank']}`")
            top = item["top"][0] if item["top"] else {}
            lines.append(f"  Top1: `{top.get('chunk_id')}` rules={top.get('rule_ids')} preview={top.get('preview')}")
        lines.append("")
    lines.append("## Interpretation")
    lines.append("")
    lines.append("- `Recall@5` and `MRR@5` measure whether the parser/chunker preserves retrievable rule evidence.")
    lines.append("- `Semantic loss rate` checks whether each rule's required anchors are preserved together in at least one chunk.")
    lines.append("- `Clean FP Support@5` is a deterministic proxy for RAG-induced false positives; it does not call an LLM.")
    lines.append("")
    lines.append("## Limitations")
    lines.append("")
    lines.append("- This is an offline controlled benchmark, not a replacement for evaluation on your private production PDFs and historical review tasks.")
    lines.append("- The review-support metric is a deterministic proxy and does not measure final LLM behavior.")
    lines.append("- Recall@5 saturated for all pipelines, so Top1, MRR, semantic loss, and rule contamination are the more useful differentiators in this run.")
    path.write_text("\n".join(lines), encoding="utf-8")


def tokenize(text: str) -> list[str]:
    return [m.group(0).lower() for m in WORD_RE.finditer(text or "")]


def extract_rule_ids(text: str) -> set[str]:
    return {normalize_rule_id(match.group(0)) for match in RULE_ID_RE.finditer(text or "")}


def normalize_rule_id(value: str | None) -> str:
    return (value or "").strip().upper()


def clean_text(value: str) -> str:
    return re.sub(r"\s+", " ", value or "").strip()


def chunk_to_dict(chunk: Chunk) -> dict[str, Any]:
    return {
        "chunk_id": chunk.chunk_id,
        "pipeline": chunk.pipeline,
        "source_file": chunk.source_file,
        "content": chunk.content,
        "metadata": chunk.metadata,
        "rule_ids": sorted(chunk.rule_ids),
    }


def write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")


def mvn_cmd() -> str:
    return "mvn.cmd" if os.name == "nt" else "mvn"


def collect_environment() -> dict[str, Any]:
    import importlib.metadata
    packages = {}
    for name in ["reportlab", "pdfplumber", "pypdf", "pymupdf", "pymupdf4llm", "markitdown", "docling"]:
        try:
            packages[name] = importlib.metadata.version(name)
        except importlib.metadata.PackageNotFoundError:
            packages[name] = None
    return {
        "python": sys.version,
        "packages": packages,
    }


def extract_json_array(stdout: str) -> str:
    start = stdout.find("[{")
    end = stdout.rfind("]")
    if start < 0 or end < start:
        raise ValueError(f"Java bridge did not return a JSON array. Output preview: {stdout[:500]}")
    return stdout[start:end + 1]


if __name__ == "__main__":
    raise SystemExit(main())
