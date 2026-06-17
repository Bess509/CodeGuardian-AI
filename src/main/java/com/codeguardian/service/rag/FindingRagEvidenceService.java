package com.codeguardian.service.rag;

import com.codeguardian.entity.Finding;
import com.codeguardian.service.provenance.EvidenceDraft;
import com.codeguardian.service.provenance.ProvenanceHashService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class FindingRagEvidenceService {

    private static final int DEFAULT_TOP_K = 1;
    private static final int SOURCE_CONTEXT_LINES = 8;
    private static final int QUERY_SNIPPET_MAX = 2200;
    private static final int EVIDENCE_EXCERPT_MAX = 4000;
    private static final Pattern KEYWORD_PATTERN = Pattern.compile(
            "\\b(?:CWE-\\d+|OWASP|SQL|XSS|CSRF|SSRF|JWT|MD5|SHA1|SHA-1|SHA256|AES|RSA|[A-Za-z][A-Za-z0-9_-]{2,})\\b");

    private final KnowledgeBaseService knowledgeBaseService;
    private final ProvenanceHashService hashService;

    public List<EvidenceDraft> retrieveForFinding(Finding finding,
                                                  String sourceRef,
                                                  String language,
                                                  String codeContent) {
        return retrieveForFinding(finding, sourceRef, language, codeContent, DEFAULT_TOP_K);
    }

    public List<EvidenceDraft> retrieveForFinding(Finding finding,
                                                  String sourceRef,
                                                  String language,
                                                  String codeContent,
                                                  int topK) {
        if (finding == null) {
            return List.of();
        }
        int requestedTopK = Math.max(1, topK);
        String query = buildQuery(finding, sourceRef, language, codeContent);
        try {
            List<RetrievedKnowledgeChunk> chunks = knowledgeBaseService.searchSnippetChunks(query, requestedTopK);
            if (chunks == null || chunks.isEmpty()) {
                return List.of();
            }
            return chunks.stream()
                    .limit(requestedTopK)
                    .map(chunk -> toEvidenceDraft(finding, chunk, query, sourceRef, language, requestedTopK))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Finding-scoped RAG retrieval failed for findingId={}: {}",
                    finding.getId(), e.getMessage());
            return List.of();
        }
    }

    String buildQuery(Finding finding, String sourceRef, String language, String codeContent) {
        String sourceSnippet = extractSourceSnippet(codeContent, finding.getStartLine(), finding.getEndLine());
        List<String> keywords = extractKeywords(finding);
        StringBuilder query = new StringBuilder();
        query.append("Finding-scoped code review evidence query\n");
        query.append("Language: ").append(firstNonBlank(language, "Unknown")).append('\n');
        query.append("Source Ref: ").append(firstNonBlank(sourceRef, "unknown")).append('\n');
        query.append("Rule Categories: ").append(firstNonBlank(finding.getCategory(), "CODE_REVIEW")).append('\n');
        query.append("Risk Keywords: ").append(String.join(", ", keywords)).append('\n');
        query.append("Finding Title: ").append(firstNonBlank(finding.getTitle(), "")).append('\n');
        query.append("Finding Description: ").append(firstNonBlank(finding.getDescription(), "")).append('\n');
        query.append("Finding Suggestion: ").append(firstNonBlank(finding.getSuggestion(), "")).append('\n');
        query.append("Finding Source: ").append(firstNonBlank(finding.getSource(), "")).append('\n');
        if (!sourceSnippet.isBlank()) {
            query.append("Source Snippet:\n").append(trim(sourceSnippet, QUERY_SNIPPET_MAX));
        }
        return query.toString().trim();
    }

    private EvidenceDraft toEvidenceDraft(Finding finding,
                                          RetrievedKnowledgeChunk chunk,
                                          String query,
                                          String sourceRef,
                                          String language,
                                          int requestedTopK) {
        String excerpt = trim(chunk.getContent(), EVIDENCE_EXCERPT_MAX);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("retrievalScope", "FINDING");
        metadata.put("queryHash", hashService.sha256Hex(query));
        metadata.put("queryPreview", trim(query, 800));
        metadata.put("requestedTopK", requestedTopK);
        metadata.put("findingId", finding.getId());
        metadata.put("findingTitle", finding.getTitle());
        metadata.put("findingCategory", finding.getCategory());
        metadata.put("sourceRef", sourceRef);
        metadata.put("language", language);
        metadata.put("rank", chunk.getRank());
        metadata.put("retrievalMode", chunk.getRetrievalMode());
        metadata.put("sourceDocumentId", chunk.getSourceDocumentId());
        metadata.put("chunkId", chunk.getChunkId());
        metadata.put("title", chunk.getTitle());
        metadata.put("score", chunk.getScore());
        metadata.put("sourceMetadata", chunk.getMetadata());

        return EvidenceDraft.builder()
                .evidenceType("RAG_SNIPPET")
                .sourceName("KnowledgeBaseService")
                .sourceRef(chunk.getSourceRef())
                .locator(chunk.getChunkId())
                .excerpt(excerpt)
                .contentHash(hashService.sha256Hex(excerpt))
                .relevanceScore(chunk.getScore())
                .metadata(metadata)
                .build();
    }

    private List<String> extractKeywords(Finding finding) {
        Set<String> keywords = new LinkedHashSet<>();
        addKeyword(keywords, finding.getCategory());
        addKeyword(keywords, finding.getTitle());

        String combined = String.join(" ",
                firstNonBlank(finding.getTitle(), ""),
                firstNonBlank(finding.getDescription(), ""),
                firstNonBlank(finding.getSuggestion(), ""));
        Matcher matcher = KEYWORD_PATTERN.matcher(combined);
        while (matcher.find() && keywords.size() < 14) {
            addKeyword(keywords, matcher.group());
        }
        return new ArrayList<>(keywords);
    }

    private void addKeyword(Set<String> keywords, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() >= 3) {
            keywords.add(normalized);
        }
    }

    private String extractSourceSnippet(String codeContent, Integer startLine, Integer endLine) {
        if (codeContent == null || codeContent.isBlank()) {
            return "";
        }
        String[] lines = codeContent.split("\\r?\\n", -1);
        int start = startLine != null && startLine > 0 ? startLine : 1;
        int end = endLine != null && endLine >= start ? endLine : start;
        int from = Math.max(1, start - SOURCE_CONTEXT_LINES);
        int to = Math.min(lines.length, end + SOURCE_CONTEXT_LINES);
        StringBuilder snippet = new StringBuilder();
        for (int line = from; line <= to; line++) {
            if (line - 1 >= 0 && line - 1 < lines.length) {
                snippet.append(line).append(": ").append(lines[line - 1]).append('\n');
            }
        }
        return snippet.toString().trim();
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    private String trim(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "\n... [truncated]";
    }
}
