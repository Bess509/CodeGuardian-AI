package com.codeguardian.service;

import com.codeguardian.config.AIConfigProperties;
import com.codeguardian.entity.Finding;
import com.codeguardian.enums.ModelProviderEnum;
import com.codeguardian.service.ai.PromptService;
import com.codeguardian.service.ai.context.ReviewContextHolder;
import com.codeguardian.service.ai.factory.ChatClientFactory;
import com.codeguardian.service.ai.output.CodeReviewOutputParser;
import com.codeguardian.service.ai.tool.ToolRegistry;
import com.codeguardian.service.provenance.EvidenceDraft;
import com.codeguardian.service.provenance.ProvenanceHashService;
import com.codeguardian.service.rag.KnowledgeBaseService;
import com.codeguardian.service.rag.RetrievedKnowledgeChunk;
import com.codeguardian.service.rag.TaskRagPack;
import com.codeguardian.service.rag.TaskRagPackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AIModelService {

    private static final int RAG_RECALL_TOP_K = 8;
    private static final int RAG_PROMPT_TOP_K = 5;
    private static final int RAG_QUERY_PREVIEW_MAX = 600;

    private final ChatClientFactory chatClientFactory;
    private final PromptService promptService;
    private final CodeReviewOutputParser outputParser;
    private final KnowledgeBaseService knowledgeBaseService;
    private final AIConfigProperties aiConfigProperties;
    private final ToolRegistry toolRegistry;
    private final ProvenanceHashService hashService;
    private final RagQueryBuilder ragQueryBuilder = new RagQueryBuilder();
    @Autowired(required = false)
    @Lazy
    private TaskRagPackService taskRagPackService;

    public List<Finding> reviewCode(String codeContent, String language) {
        return reviewCode(codeContent, language, null, true);
    }

    public List<Finding> reviewCode(String codeContent, String language, String modelProvider, boolean enableRag) {
        return reviewCode(codeContent, language, modelProvider, enableRag, null, null);
    }

    public List<Finding> reviewCode(String codeContent, String language, String modelProvider,
                                    boolean enableRag, List<Finding> existingFindings) {
        return reviewCode(codeContent, language, modelProvider, enableRag, null, existingFindings);
    }

    public List<Finding> reviewCode(String codeContent, String language, String modelProvider,
                                    boolean enableRag, String sourceRef, List<Finding> existingFindings) {
        if (Boolean.FALSE.equals(aiConfigProperties.getEnabled())) {
            log.warn("AI review is disabled.");
            return new ArrayList<>();
        }
        if (!chatClientFactory.hasAvailableProviders()) {
            log.warn("No available AI model provider.");
            return new ArrayList<>();
        }

        String safeCode = safe(codeContent);
        String safeLanguage = firstNonBlank(language, "Unknown");
        String safeSourceRef = firstNonBlank(sourceRef, "code-snippet");
        List<Finding> seedFindings = existingFindings != null ? existingFindings : List.of();

        try {
            ReviewContextHolder.clearFindingsAndEvidence();

            String ragContext = "";
            if (enableRag) {
                ragContext = retrieveContext(safeCode, safeLanguage, safeSourceRef, seedFindings);
            }

            Prompt prompt = promptService.buildCodeReviewPrompt(safeCode, safeLanguage, ragContext, seedFindings);
            captureEvidence(EvidenceDraft.builder()
                    .evidenceType("PROMPT")
                    .sourceName("PromptService")
                    .sourceRef(safeSourceRef)
                    .excerpt(trimForEvidence(String.valueOf(prompt)))
                    .metadata(meta(
                            "language", safeLanguage,
                            "sourceRef", safeSourceRef,
                            "provider", modelProvider,
                            "ragEnabled", enableRag,
                            "seedFindingCount", seedFindings.size(),
                            "promptLength", String.valueOf(prompt).length()
                    ))
                    .build());

            ModelProviderEnum provider = ModelProviderEnum.from(modelProvider).orElse(null);
            ChatClient chatClient = chatClientFactory.createChatClient(provider);
            List<FunctionCallback> callbacks = new ArrayList<>(toolRegistry.getFunctionCallbacks());
            if (!seedFindings.isEmpty()) {
                callbacks.removeIf(callback -> "semgrepAnalysis".equals(callback.getName()));
            }

            ChatClient.ChatClientRequestSpec requestSpec = chatClient.prompt(prompt);
            if (!callbacks.isEmpty()) {
                requestSpec = requestSpec.functions(callbacks.toArray(new FunctionCallback[0]));
            }

            String response = requestSpec.call().content();
            captureEvidence(EvidenceDraft.builder()
                    .evidenceType("MODEL_RESPONSE")
                    .sourceName("AIModelService")
                    .sourceRef(provider != null ? provider.name() : firstNonBlank(modelProvider, "default"))
                    .excerpt(trimForEvidence(response))
                    .metadata(meta(
                            "language", safeLanguage,
                            "sourceRef", safeSourceRef,
                            "provider", provider != null ? provider.name() : modelProvider,
                            "responseLength", response != null ? response.length() : 0
                    ))
                    .build());

            List<Finding> findings = outputParser.parse(response);
            if (findings == null) {
                findings = new ArrayList<>();
            }

            List<Finding> toolFindings = ReviewContextHolder.getFindings();
            if (toolFindings != null && !toolFindings.isEmpty()) {
                for (Finding toolFinding : toolFindings) {
                    if (!isDuplicate(toolFinding, findings)) {
                        findings.add(toolFinding);
                    }
                }
            }

            logFindingStats(findings, safeLanguage, enableRag);
            return findings;
        } catch (Exception e) {
            log.error("AI review failed: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    public List<ModelProviderEnum> getAvailableProviders() {
        return chatClientFactory.getAvailableProviders();
    }

    private String retrieveContext(String code, String language) {
        return retrieveContext(code, language, null, null);
    }

    private String retrieveContext(String code, String language, String sourceRef, List<Finding> seedFindings) {
        try {
            Optional<TaskRagPack> taskPack = loadTaskRagPack();
            if (taskPack.isPresent()) {
                TaskRagPack pack = taskPack.get();
                String packContext = pack.toPromptContext(RAG_PROMPT_TOP_K);
                if (!packContext.isBlank()) {
                    captureEvidence(EvidenceDraft.builder()
                            .evidenceType("TASK_RAG_PACK_USED")
                            .sourceName("TaskRagPackService")
                            .sourceRef(pack.getSourceRef())
                            .locator(pack.getPackId())
                            .excerpt(trimForEvidence(packContext))
                            .contentHash(safeHash(packContext))
                            .relevanceScore(1.0d)
                            .metadata(meta(
                                    "taskId", pack.getTaskId(),
                                    "packId", pack.getPackId(),
                                    "packContentHash", pack.getContentHash(),
                                    "sourceRef", sourceRef,
                                    "language", language,
                                    "promptTopK", RAG_PROMPT_TOP_K,
                                    "chunkCount", pack.getItems() != null ? pack.getItems().size() : 0,
                                    "queryStrategy", pack.getQueryStrategy(),
                                    "queryHash", safeHash(pack.getQueryText())
                            ))
                            .build());
                    return packContext;
                }
            }

            RagQuery ragQuery = ragQueryBuilder.build(code, language, sourceRef, seedFindings);
            List<RetrievedKnowledgeChunk> chunks = knowledgeBaseService.searchSnippetChunks(ragQuery.text(), RAG_RECALL_TOP_K);
            if (chunks == null || chunks.isEmpty()) {
                log.info("RAG returned no context for {}", sourceRef);
                return "";
            }

            List<RetrievedKnowledgeChunk> selectedChunks = chunks.stream()
                    .limit(RAG_PROMPT_TOP_K)
                    .collect(Collectors.toList());

            StringBuilder context = new StringBuilder();
            context.append("Relevant review rules and repair references:\n");
            for (RetrievedKnowledgeChunk chunk : selectedChunks) {
                context.append("- Retrieval mode: ")
                        .append(firstNonBlank(chunk.getRetrievalMode(), "UNKNOWN"))
                        .append(", source: ")
                        .append(firstNonBlank(chunk.getSourceRef(), chunk.getSourceDocumentId()))
                        .append("\n")
                        .append(chunk.toPromptSnippet())
                        .append("\n\n");

                captureEvidence(EvidenceDraft.builder()
                        .evidenceType("RAG_SNIPPET")
                        .sourceName("KnowledgeBaseService")
                        .sourceRef(chunk.getSourceRef())
                        .locator(chunk.getChunkId())
                        .excerpt(trimForEvidence(chunk.getContent()))
                        .contentHash(safeHash(chunk.getContent()))
                        .relevanceScore(chunk.getScore())
                        .metadata(meta(
                                "queryHash", safeHash(ragQuery.text()),
                                "queryStrategy", ragQuery.strategy(),
                                "queryPreview", trim(ragQuery.text(), RAG_QUERY_PREVIEW_MAX),
                                "sourceRef", sourceRef,
                                "targetLines", ragQuery.targetLines(),
                                "riskKeywords", ragQuery.riskKeywords(),
                                "ruleCategories", ragQuery.ruleCategories(),
                                "recallTopK", RAG_RECALL_TOP_K,
                                "promptTopK", RAG_PROMPT_TOP_K,
                                "rank", chunk.getRank(),
                                "language", language,
                                "retrievalMode", chunk.getRetrievalMode(),
                                "sourceDocumentId", chunk.getSourceDocumentId(),
                                "chunkId", chunk.getChunkId(),
                                "title", chunk.getTitle(),
                                "score", chunk.getScore(),
                                "sourceMetadata", chunk.getMetadata()
                        ))
                        .build());
            }
            return context.toString().trim();
        } catch (Exception e) {
            log.warn("RAG context retrieval failed: {}", e.getMessage());
            return "";
        }
    }

    private Optional<TaskRagPack> loadTaskRagPack() {
        if (taskRagPackService == null) {
            return Optional.empty();
        }
        Long taskId = ReviewContextHolder.getTaskId();
        if (taskId == null) {
            return Optional.empty();
        }
        try {
            return taskRagPackService.find(taskId);
        } catch (Exception e) {
            log.warn("Task RAG pack lookup failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    String buildRagQueryText(String code, String language, String sourceRef, List<Finding> seedFindings) {
        return ragQueryBuilder.build(code, language, sourceRef, seedFindings).text();
    }

    private boolean isDuplicate(Finding newFinding, List<Finding> existingFindings) {
        if (newFinding == null || existingFindings == null || existingFindings.isEmpty()) {
            return false;
        }
        for (Finding existing : existingFindings) {
            if (existing == null) {
                continue;
            }
            boolean sameLine = Objects.equals(existing.getStartLine(), newFinding.getStartLine());
            boolean sameCategory = Objects.equals(existing.getCategory(), newFinding.getCategory());
            boolean sameTitle = Objects.equals(existing.getTitle(), newFinding.getTitle());
            if (sameLine && (sameCategory || sameTitle)) {
                return true;
            }
        }
        return false;
    }

    private void logFindingStats(List<Finding> findings, String language, boolean ragEnabled) {
        int count = findings != null ? findings.size() : 0;
        Map<String, Long> categories = findings == null ? Map.of() : findings.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(f -> firstNonBlank(f.getCategory(), "UNKNOWN"),
                        LinkedHashMap::new, Collectors.counting()));
        log.info("AI review completed: language={}, ragEnabled={}, findingCount={}, categories={}",
                language, ragEnabled, count, categories);
    }

    private void captureEvidence(EvidenceDraft draft) {
        if (draft == null) {
            return;
        }
        if (draft.getContentHash() == null && draft.getExcerpt() != null) {
            draft.setContentHash(safeHash(draft.getExcerpt()));
        }
        ReviewContextHolder.addEvidence(draft);
    }

    private String trimForEvidence(String text) {
        return trim(text, 1600);
    }

    private Map<String, Object> meta(Object... keyValues) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (keyValues == null) {
            return metadata;
        }
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object key = keyValues[i];
            Object value = keyValues[i + 1];
            if (key != null && value != null) {
                metadata.put(String.valueOf(key), value);
            }
        }
        return metadata;
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : (fallback != null ? fallback : "");
    }

    private String safe(String value) {
        return value != null ? value : "";
    }

    private String trim(String value, int maxLength) {
        String safeValue = safe(value).trim();
        if (safeValue.length() <= maxLength) {
            return safeValue;
        }
        return safeValue.substring(0, Math.max(0, maxLength - 15)) + "... (truncated)";
    }

    private String safeHash(String value) {
        try {
            if (hashService != null) {
                return hashService.sha256Hex(value);
            }
        } catch (Exception ignored) {
            // Fall through to a deterministic but weaker fallback for evidence metadata.
        }
        return Integer.toHexString(Objects.hashCode(value));
    }

}
