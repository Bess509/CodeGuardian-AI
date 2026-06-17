package com.codeguardian.service;

import com.codeguardian.dto.ReviewRequestDTO;
import com.codeguardian.entity.Finding;
import com.codeguardian.enums.ModelProviderEnum;
import com.codeguardian.service.ai.context.ReviewContextHolder;
import com.codeguardian.service.agent.AgentReviewResult;
import com.codeguardian.service.agent.ReviewAgentOrchestrator;
import com.codeguardian.service.cache.SemanticFingerprintCacheService;
import com.codeguardian.service.provenance.EvidenceDraft;
import com.codeguardian.service.provenance.ReviewAuditService;
import com.codeguardian.service.rules.RuleEngineService;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
final class ReviewStrategyExecutor {

    private final AIModelService aiModelService;
    private final RuleEngineService ruleEngineService;
    private final SystemConfigService configService;
    private final SemanticFingerprintCacheService fingerprintCacheService;
    private final ReviewAgentOrchestrator reviewAgentOrchestrator;
    private final ReviewAuditService auditService;

    ReviewStrategyExecutor(AIModelService aiModelService,
                           RuleEngineService ruleEngineService,
                           SystemConfigService configService,
                           SemanticFingerprintCacheService fingerprintCacheService,
                           ReviewAgentOrchestrator reviewAgentOrchestrator,
                           ReviewAuditService auditService) {
        this.aiModelService = aiModelService;
        this.ruleEngineService = ruleEngineService;
        this.configService = configService;
        this.fingerprintCacheService = fingerprintCacheService;
        this.reviewAgentOrchestrator = reviewAgentOrchestrator;
        this.auditService = auditService;
    }

    List<Finding> execute(String codeContent, String language, ReviewRequestDTO request,
                          Long taskId, String sourceRef) {
        ReviewContextHolder.setTaskId(taskId);
        boolean useRulesOnly = Boolean.TRUE.equals(request.getRulesOnly());
        boolean useAgent = Boolean.TRUE.equals(request.getAgentMode()) && reviewAgentOrchestrator != null;
        String mode = useAgent ? "AGENT" : (useRulesOnly ? "RULE_ENGINE" : "AI_MODEL");
        List<Finding> findings;
        recordAudit(taskId, "REVIEW_STRATEGY_STARTED", "ANALYSIS",
                "Review strategy execution started",
                meta(
                        "sourceRef", sourceRef,
                        "language", language,
                        "mode", mode,
                        "rulesOnly", useRulesOnly,
                        "agentMode", request.getAgentMode(),
                        "enableRag", request.getEnableRag(),
                        "requestedProvider", request.getModelProvider(),
                        "ruleTemplate", request.getRuleTemplate()
                ));

        if (useAgent) {
            AgentReviewResult agentResult = reviewAgentOrchestrator.executeWithEvidence(codeContent, language, request, taskId, sourceRef);
            findings = agentResult.getFindings();
            ReviewContextHolder.addEvidence(agentResult.getEvidenceDrafts());
        } else if (useRulesOnly) {
            findings = executeRuleReview(codeContent, language, request, taskId, sourceRef);
        } else {
            findings = executeAiReview(codeContent, language, request, taskId, sourceRef);
        }

        int rawFindingCount = findings != null ? findings.size() : 0;
        findings = filterFindings(findings, taskId, sourceRef, language);
        recordAudit(taskId, "REVIEW_STRATEGY_COMPLETED", "ANALYSIS",
                "Review strategy execution completed",
                meta(
                        "sourceRef", sourceRef,
                        "language", language,
                        "mode", mode,
                        "rawFindingCount", rawFindingCount,
                        "findingCount", findings.size()
                ));
        return findings;
    }

    private List<Finding> executeRuleReview(String codeContent, String language, ReviewRequestDTO request,
                                            Long taskId, String sourceRef) {
        List<Finding> findings;
        if ("CUSTOM".equalsIgnoreCase(request.getRuleTemplate())) {
            findings = ruleEngineService.reviewWithCustom(codeContent, request.getCustomRules());
        } else {
            findings = ruleEngineService.reviewWithTemplate(codeContent, language, request.getRuleTemplate());
        }
        if (findings == null) {
            findings = List.of();
        }
        findings.forEach(f -> f.setSource("RuleEngine"));
        ReviewContextHolder.addEvidence(EvidenceDraft.builder()
                .evidenceType("RULE_ENGINE")
                .sourceName("RuleEngineService")
                .sourceRef(request.getRuleTemplate() != null ? request.getRuleTemplate() : "default")
                .excerpt("Rule-based review executed for " + language)
                .metadata(meta(
                        "sourceRef", sourceRef,
                        "language", language,
                        "template", request.getRuleTemplate(),
                        "customRuleCount", request.getCustomRules() != null ? request.getCustomRules().size() : 0,
                        "findingCount", findings.size()
                ))
                .build());
        recordAudit(taskId, "RULE_REVIEW_COMPLETED", "ANALYSIS",
                "Rule-based review completed",
                meta(
                        "sourceRef", sourceRef,
                        "language", language,
                        "template", request.getRuleTemplate(),
                        "customRuleCount", request.getCustomRules() != null ? request.getCustomRules().size() : 0,
                        "findingCount", findings.size()
                ));
        return findings;
    }

    private List<Finding> executeAiReview(String codeContent, String language, ReviewRequestDTO request,
                                          Long taskId, String sourceRef) {
        boolean enableRag = request.getEnableRag() != null ? request.getEnableRag() : true;
        String requestedProvider = request.getModelProvider();
        ModelProviderEnum resolvedProvider = ModelProviderEnum.from(requestedProvider).orElse(null);
        if (resolvedProvider == null) {
            List<ModelProviderEnum> available = aiModelService.getAvailableProviders();
            if (available != null && !available.isEmpty()) {
                resolvedProvider = available.get(0);
            }
        }

        final ModelProviderEnum provider = resolvedProvider;
        final String providerNameForAi = provider != null ? provider.name() : requestedProvider;
        final int blockStartLine = 1;
        List<Finding> seedFindings = collectRuleSeedFindings(codeContent, language, request, taskId, sourceRef);
        Optional<List<Finding>> cachedFindings = fingerprintCacheService
                .tryGetCachedFindings(codeContent, language, provider, enableRag, blockStartLine);
        if (cachedFindings.isPresent()) {
            List<Finding> findings = cachedFindings.get() != null ? cachedFindings.get() : List.of();
            findings = ReviewFindingPolicy.mergeSeedFindings(findings, seedFindings);
            findings.forEach(f -> f.setSource(f.getSource() != null ? f.getSource() : "SemanticCache"));
            ReviewContextHolder.addEvidence(EvidenceDraft.builder()
                    .evidenceType("SEMANTIC_CACHE_HIT")
                    .sourceName("SemanticFingerprintCacheService")
                    .sourceRef(providerNameForAi)
                    .excerpt("Review result reused from semantic fingerprint cache.")
                    .metadata(meta(
                            "sourceRef", sourceRef,
                            "language", language,
                            "provider", providerNameForAi,
                            "enableRag", enableRag,
                            "seedFindingCount", seedFindings.size(),
                            "findingCount", findings.size()
                    ))
                    .build());
            recordAudit(taskId, "SEMANTIC_CACHE_HIT", "CACHE",
                    "Semantic fingerprint cache hit",
                    meta(
                            "sourceRef", sourceRef,
                            "language", language,
                            "provider", providerNameForAi,
                            "enableRag", enableRag,
                            "seedFindingCount", seedFindings.size(),
                            "findingCount", findings.size()
                    ));
            return findings;
        }

        recordAudit(taskId, "SEMANTIC_CACHE_MISS", "CACHE",
                "Semantic fingerprint cache miss",
                meta(
                        "sourceRef", sourceRef,
                        "language", language,
                        "provider", providerNameForAi,
                        "enableRag", enableRag
                ));
        List<Finding> findings = aiModelService.reviewCode(
                codeContent,
                language,
                providerNameForAi,
                enableRag,
                sourceRef,
                seedFindings
        );
        if (findings == null) {
            findings = List.of();
        }
        findings = ReviewFindingPolicy.mergeSeedFindings(findings, seedFindings);
        ReviewContextHolder.addEvidence(EvidenceDraft.builder()
                .evidenceType("SEMANTIC_CACHE_MISS")
                .sourceName("SemanticFingerprintCacheService")
                .sourceRef(providerNameForAi)
                .excerpt("No reusable semantic fingerprint cache entry was found.")
                .metadata(meta(
                        "sourceRef", sourceRef,
                        "language", language,
                        "provider", providerNameForAi,
                        "enableRag", enableRag
                ))
                .build());
        recordAudit(taskId, "AI_REVIEW_COMPLETED", "MODEL",
                "AI model review completed",
                meta(
                        "sourceRef", sourceRef,
                        "language", language,
                        "provider", providerNameForAi,
                        "enableRag", enableRag,
                        "findingCount", findings.size()
                ));
        fingerprintCacheService.storeFindings(codeContent, language, provider, enableRag, blockStartLine, findings);
        ReviewContextHolder.addEvidence(EvidenceDraft.builder()
                .evidenceType("SEMANTIC_CACHE_STORE")
                .sourceName("SemanticFingerprintCacheService")
                .sourceRef(providerNameForAi)
                .excerpt("Fresh review result stored in semantic fingerprint cache.")
                .metadata(meta(
                        "sourceRef", sourceRef,
                        "language", language,
                        "provider", providerNameForAi,
                        "enableRag", enableRag,
                        "findingCount", findings.size()
                ))
                .build());
        recordAudit(taskId, "SEMANTIC_CACHE_STORE", "CACHE",
                "Fresh review result stored in semantic fingerprint cache",
                meta(
                        "sourceRef", sourceRef,
                        "language", language,
                        "provider", providerNameForAi,
                        "enableRag", enableRag,
                        "findingCount", findings.size()
                ));
        return findings;
    }

    private List<Finding> filterFindings(List<Finding> findings, Long taskId, String sourceRef, String language) {
        try {
            List<Finding> normalized = findings != null ? findings : List.of();
            Map<String, Boolean> categories = configService.getSettings().getRuleCategories();
            if (categories != null && !categories.isEmpty()) {
                return normalized.stream()
                        .filter(f -> ReviewFindingPolicy.isCategoryEnabled(f.getCategory(), categories))
                        .collect(Collectors.toList());
            }
            return normalized;
        } catch (Exception e) {
            recordAudit(taskId, "FINDING_FILTER_FAILED", "ANALYSIS",
                    "Finding category filter failed",
                    meta(
                            "sourceRef", sourceRef,
                            "language", language,
                            "error", e.getMessage(),
                            "errorType", e.getClass().getName()
                    ));
            log.warn("Finding category filter failed, returning unfiltered results", e);
            return findings != null ? findings : List.of();
        }
    }

    private List<Finding> collectRuleSeedFindings(String codeContent, String language, ReviewRequestDTO request,
                                                  Long taskId, String sourceRef) {
        try {
            List<Finding> seeds;
            if ("CUSTOM".equalsIgnoreCase(request.getRuleTemplate())) {
                seeds = ruleEngineService.reviewWithCustom(codeContent, request.getCustomRules());
            } else {
                seeds = ruleEngineService.reviewWithTemplate(codeContent, language, request.getRuleTemplate());
            }
            if (seeds == null || seeds.isEmpty()) {
                recordAudit(taskId, "RULE_SEED_FINDINGS_COLLECTED", "ANALYSIS",
                        "No rule findings were available as deterministic review seeds",
                        meta("sourceRef", sourceRef, "language", language, "findingCount", 0));
                return List.of();
            }
            List<Finding> normalizedSeeds = seeds.stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(ArrayList::new));
            normalizedSeeds.forEach(f -> f.setSource("RuleEngine"));
            ReviewContextHolder.addEvidence(EvidenceDraft.builder()
                    .evidenceType("RULE_ENGINE_SEED")
                    .sourceName("RuleEngineService")
                    .sourceRef(sourceRef)
                    .excerpt("Rule findings used as deterministic review anchors.")
                    .metadata(meta(
                            "sourceRef", sourceRef,
                            "language", language,
                            "template", request.getRuleTemplate(),
                            "customRuleCount", request.getCustomRules() != null ? request.getCustomRules().size() : 0,
                            "findingCount", normalizedSeeds.size()
                    ))
                    .build());
            recordAudit(taskId, "RULE_SEED_FINDINGS_COLLECTED", "ANALYSIS",
                    "Rule findings collected as deterministic review seeds",
                    meta(
                            "sourceRef", sourceRef,
                            "language", language,
                            "template", request.getRuleTemplate(),
                            "findingCount", normalizedSeeds.size()
                    ));
            return normalizedSeeds;
        } catch (Exception e) {
            recordAudit(taskId, "RULE_SEED_FINDINGS_FAILED", "ANALYSIS",
                    "Rule findings could not be collected as deterministic review seeds",
                    meta("sourceRef", sourceRef, "language", language, "error", e.getMessage(),
                            "errorType", e.getClass().getName()));
            log.warn("Failed to collect RAG seed findings for {}: {}", sourceRef, e.getMessage());
            return List.of();
        }
    }

    private void recordAudit(Long taskId, String eventType, String stage, String message, Map<String, Object> metadata) {
        if (taskId == null || auditService == null) {
            return;
        }
        auditService.record(taskId, eventType, stage, "system", message, metadata);
    }

    private Map<String, Object> meta(Object... keyValues) {
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
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
}
