package com.codeguardian.service.provenance;

import com.codeguardian.entity.Finding;
import com.codeguardian.entity.ReviewEvidence;
import com.codeguardian.entity.ReviewTask;
import com.codeguardian.repository.FindingRepository;
import com.codeguardian.repository.ReviewEvidenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ReviewProvenanceService {

    private static final int CONTEXT_LINES = 2;
    private static final int MAX_CONTEXT_EVIDENCE_PER_FINDING = 8;
    private static final List<String> FINDING_CONTEXT_EVIDENCE_TYPES = List.of(
            "RAG_SNIPPET",
            "RULE_ENGINE",
            "TOOL_CALL",
            "SEMANTIC_CACHE_HIT",
            "MODEL_RESPONSE"
    );

    private final ReviewEvidenceRepository evidenceRepository;
    private final FindingRepository findingRepository;
    private final ProvenanceHashService hashService;

    public List<ReviewEvidence> persistTaskEvidence(ReviewTask task, List<EvidenceDraft> drafts) {
        if (task == null || task.getId() == null || drafts == null || drafts.isEmpty()) {
            return List.of();
        }
        List<ReviewEvidence> saved = new ArrayList<>();
        for (EvidenceDraft draft : drafts) {
            saved.add(evidenceRepository.save(toEntity(task.getId(), null, draft)));
        }
        return saved;
    }

    public List<ReviewEvidence> attachContextEvidenceToFinding(ReviewTask task,
                                                               Finding finding,
                                                               List<EvidenceDraft> drafts) {
        if (task == null || task.getId() == null || finding == null || finding.getId() == null
                || drafts == null || drafts.isEmpty()) {
            return List.of();
        }
        List<ReviewEvidence> saved = new ArrayList<>();
        for (EvidenceDraft draft : drafts) {
            if (!isFindingContextEvidence(draft)) {
                continue;
            }
            EvidenceDraft linkedDraft = copyForFinding(finding, draft);
            saved.add(evidenceRepository.save(toEntity(task.getId(), finding.getId(), linkedDraft)));
            if (saved.size() >= MAX_CONTEXT_EVIDENCE_PER_FINDING) {
                break;
            }
        }
        updateFindingGrounding(finding, saved);
        return saved;
    }

    public List<ReviewEvidence> groundFindingWithSource(ReviewTask task,
                                                        Finding finding,
                                                        String sourceRef,
                                                        String language,
                                                        String codeContent) {
        if (task == null || finding == null || finding.getId() == null || codeContent == null) {
            return List.of();
        }

        SourceExcerpt excerpt = extractExcerpt(codeContent, finding.getStartLine(), finding.getEndLine());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("language", language);
        metadata.put("sourceCodeHash", hashService.sha256Hex(codeContent));
        metadata.put("findingSource", finding.getSource());

        EvidenceDraft draft = EvidenceDraft.builder()
                .evidenceType("SOURCE_CODE")
                .sourceName(language != null ? language : "code")
                .sourceRef(sourceRef)
                .locator(buildLocator(sourceRef, excerpt.getStartLine(), excerpt.getEndLine()))
                .startLine(excerpt.getStartLine())
                .endLine(excerpt.getEndLine())
                .excerpt(excerpt.getText())
                .contentHash(hashService.sha256Hex(excerpt.getText()))
                .relevanceScore(1.0d)
                .metadata(metadata)
                .build();

        ReviewEvidence evidence = evidenceRepository.save(toEntity(task.getId(), finding.getId(), draft));
        updateFindingGrounding(finding, List.of(evidence));
        return List.of(evidence);
    }

    public void updateFindingGrounding(Finding finding, List<ReviewEvidence> evidence) {
        if (finding == null || finding.getId() == null) {
            return;
        }
        long count = evidenceRepository.countByFindingId(finding.getId());
        List<ReviewEvidence> allEvidence = evidenceRepository.findByFindingIdOrderByCreatedAtAscIdAsc(finding.getId());
        if (allEvidence == null) {
            allEvidence = List.of();
        }
        if (allEvidence.isEmpty() && evidence != null && !evidence.isEmpty()) {
            allEvidence = evidence;
        }

        String combinedHash = allEvidence.stream()
                .map(ReviewEvidence::getContentHash)
                .filter(h -> h != null && !h.isBlank())
                .reduce("", (left, right) -> hashService.sha256Hex(left + right));

        finding.setEvidenceCount((int) count);
        finding.setGrounded(count > 0);
        finding.setEvidenceHash(combinedHash.isBlank() ? null : combinedHash);
        finding.setGroundingSummary(count > 0
                ? "Grounded by " + count + " evidence record(s). Verify evidenceHash against review_evidence.content_hash."
                : "No direct evidence record was captured.");
        findingRepository.save(finding);
    }

    private boolean isFindingContextEvidence(EvidenceDraft draft) {
        if (draft == null || draft.getEvidenceType() == null) {
            return false;
        }
        return FINDING_CONTEXT_EVIDENCE_TYPES.contains(draft.getEvidenceType());
    }

    private EvidenceDraft copyForFinding(Finding finding, EvidenceDraft draft) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (draft.getMetadata() != null) {
            metadata.putAll(draft.getMetadata());
        }
        metadata.put("linkedToFindingId", finding.getId());
        metadata.put("linkReason", "review_context");
        metadata.put("findingSeverity", finding.getSeverity());
        metadata.put("findingCategory", finding.getCategory());
        metadata.put("findingLocation", finding.getLocation());

        return EvidenceDraft.builder()
                .evidenceType(draft.getEvidenceType())
                .sourceName(draft.getSourceName())
                .sourceRef(draft.getSourceRef())
                .locator(draft.getLocator())
                .startLine(draft.getStartLine())
                .endLine(draft.getEndLine())
                .excerpt(draft.getExcerpt())
                .contentHash(draft.getContentHash())
                .relevanceScore(draft.getRelevanceScore())
                .metadata(metadata)
                .build();
    }

    private ReviewEvidence toEntity(Long taskId, Long findingId, EvidenceDraft draft) {
        String excerpt = trim(draft.getExcerpt(), 4000);
        String contentHash = hashService.sha256Hex(excerpt != null ? excerpt : "");
        return ReviewEvidence.builder()
                .taskId(taskId)
                .findingId(findingId)
                .evidenceType(draft.getEvidenceType())
                .sourceName(draft.getSourceName())
                .sourceRef(draft.getSourceRef())
                .locator(draft.getLocator())
                .startLine(draft.getStartLine())
                .endLine(draft.getEndLine())
                .excerpt(excerpt)
                .contentHash(contentHash)
                .relevanceScore(draft.getRelevanceScore())
                .metadata(draft.getMetadata())
                .build();
    }

    private SourceExcerpt extractExcerpt(String codeContent, Integer startLine, Integer endLine) {
        String[] lines = codeContent.split("\\r?\\n", -1);
        int start = startLine != null && startLine > 0 ? startLine : 1;
        int end = endLine != null && endLine >= start ? endLine : start;
        int from = Math.max(1, start - CONTEXT_LINES);
        int to = Math.min(lines.length, end + CONTEXT_LINES);
        StringBuilder text = new StringBuilder();
        for (int line = from; line <= to; line++) {
            if (line - 1 >= 0 && line - 1 < lines.length) {
                text.append(line).append(": ").append(lines[line - 1]).append('\n');
            }
        }
        return new SourceExcerpt(from, to, text.toString().trim());
    }

    private String buildLocator(String sourceRef, Integer startLine, Integer endLine) {
        String ref = sourceRef != null ? sourceRef : "unknown";
        if (startLine == null) {
            return ref;
        }
        if (endLine == null || endLine.equals(startLine)) {
            return ref + ":" + startLine;
        }
        return ref + ":" + startLine + "-" + endLine;
    }

    private String trim(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "\n... [truncated]";
    }

    @lombok.Value
    private static class SourceExcerpt {
        Integer startLine;
        Integer endLine;
        String text;
    }
}
