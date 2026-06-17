package com.codeguardian.service;

import com.codeguardian.dto.ReviewRequestDTO;
import com.codeguardian.entity.Finding;
import com.codeguardian.entity.ReviewTask;
import com.codeguardian.repository.FindingRepository;
import com.codeguardian.repository.ReviewTaskRepository;
import com.codeguardian.service.ai.context.ReviewContextHolder;
import com.codeguardian.service.agent.ReviewAgentOrchestrator;
import com.codeguardian.service.cache.SemanticFingerprintCacheService;
import com.codeguardian.service.provenance.EvidenceDraft;
import com.codeguardian.service.provenance.ReviewAuditService;
import com.codeguardian.service.provenance.ReviewProvenanceService;
import com.codeguardian.service.rag.FindingRagEvidenceService;
import com.codeguardian.service.rules.RuleEngineService;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.LongPredicate;
import java.util.stream.Collectors;

@Slf4j
final class ReviewExecutionCoordinator {

    private final ReviewTaskRepository taskRepository;
    private final FindingRepository findingRepository;
    private final AIModelService aiModelService;
    private final CodeParserService codeParserService;
    private final RuleEngineService ruleEngineService;
    private final SystemConfigService configService;
    private final GitService gitService;
    private final SemanticFingerprintCacheService fingerprintCacheService;
    private final ReviewAgentOrchestrator reviewAgentOrchestrator;
    private final ReviewAuditService auditService;
    private final ReviewProvenanceService provenanceService;
    private final FindingRagEvidenceService findingRagEvidenceService;
    private final JavaReviewGateService javaReviewGateService;
    private final ExecutorService executor;
    private final LongPredicate cancellationRequested;

    ReviewExecutionCoordinator(ReviewTaskRepository taskRepository,
                               FindingRepository findingRepository,
                               AIModelService aiModelService,
                               CodeParserService codeParserService,
                               RuleEngineService ruleEngineService,
                               SystemConfigService configService,
                               GitService gitService,
                               SemanticFingerprintCacheService fingerprintCacheService,
                                ReviewAgentOrchestrator reviewAgentOrchestrator,
                                ReviewAuditService auditService,
                                ReviewProvenanceService provenanceService,
                                FindingRagEvidenceService findingRagEvidenceService,
                                JavaReviewGateService javaReviewGateService,
                                ExecutorService executor) {
        this(taskRepository,
                findingRepository,
                aiModelService,
                codeParserService,
                ruleEngineService,
                configService,
                gitService,
                fingerprintCacheService,
                reviewAgentOrchestrator,
                auditService,
                provenanceService,
                findingRagEvidenceService,
                javaReviewGateService,
                executor,
                taskId -> false);
    }

    ReviewExecutionCoordinator(ReviewTaskRepository taskRepository,
                               FindingRepository findingRepository,
                               AIModelService aiModelService,
                               CodeParserService codeParserService,
                               RuleEngineService ruleEngineService,
                               SystemConfigService configService,
                               GitService gitService,
                               SemanticFingerprintCacheService fingerprintCacheService,
                               ReviewAgentOrchestrator reviewAgentOrchestrator,
                               ReviewAuditService auditService,
                               ReviewProvenanceService provenanceService,
                               FindingRagEvidenceService findingRagEvidenceService,
                               JavaReviewGateService javaReviewGateService,
                               ExecutorService executor,
                               LongPredicate cancellationRequested) {
        this.taskRepository = taskRepository;
        this.findingRepository = findingRepository;
        this.aiModelService = aiModelService;
        this.codeParserService = codeParserService;
        this.ruleEngineService = ruleEngineService;
        this.configService = configService;
        this.gitService = gitService;
        this.fingerprintCacheService = fingerprintCacheService;
        this.reviewAgentOrchestrator = reviewAgentOrchestrator;
        this.auditService = auditService;
        this.provenanceService = provenanceService;
        this.findingRagEvidenceService = findingRagEvidenceService;
        this.javaReviewGateService = javaReviewGateService;
        this.executor = executor;
        this.cancellationRequested = cancellationRequested != null ? cancellationRequested : taskId -> false;
    }

    void performReview(ReviewTask task, ReviewRequestDTO request) {
        ensureNotCancelled(task.getId());
        String type = request.getReviewType().toUpperCase();

        if ("DIRECTORY".equals(type) || "PROJECT".equals(type)) {
            performParallelReview(task, request);
        } else if ("GIT".equals(type)) {
            performGitReview(task, request);
        } else {
            performSingleScopeReview(task, request);
        }
    }

    List<Finding> executeReviewStrategy(String codeContent, String language, ReviewRequestDTO request) {
        return executeReviewStrategy(codeContent, language, request, null, null);
    }

    List<Finding> executeReviewStrategy(String codeContent, String language, ReviewRequestDTO request,
                                        Long taskId, String sourceRef) {
        return new ReviewStrategyExecutor(
                aiModelService,
                ruleEngineService,
                configService,
                fingerprintCacheService,
                reviewAgentOrchestrator,
                auditService
        ).execute(codeContent, language, request, taskId, sourceRef);
    }

    private void performGitReview(ReviewTask task, ReviewRequestDTO request) {
        if (request.getProjectPath() == null && request.getGitUrl() != null) {
            try {
                log.info("Git项目尚未克隆，开始克隆: {}", request.getGitUrl());
                String localPath = gitService.cloneRepository(request.getGitUrl(), request.getGitUsername(), request.getGitPassword());
                request.setProjectPath(localPath);
                task.setScope(localPath);
                taskRepository.save(task);
                log.info("Git克隆完成，本地路径: {}", localPath);
                ensureNotCancelled(task.getId());
            } catch (Exception e) {
                log.error("Git自动克隆失败", e);
                throw new RuntimeException("Git克隆失败: " + e.getMessage());
            }
        }

        if (request.getProjectPath() != null) {
            performParallelReview(task, request);
        } else {
            throw new UnsupportedOperationException("Git项目未克隆或路径为空");
        }
    }

    private void performSingleScopeReview(ReviewTask task, ReviewRequestDTO request) {
        try {
        ensureNotCancelled(task.getId());
        String codeContent = ReviewContentResolver.fetchCodeContent(request, codeParserService);
        if (codeContent == null || codeContent.trim().isEmpty()) {
            throw new IllegalArgumentException("代码内容为空");
        }
        String sourceRef = request.getFilePath() != null ? request.getFilePath() : request.getReviewType();
        ensureNotCancelled(task.getId());
        List<Finding> gateFindings = javaReviewGateService.checkSingleScope(request, codeContent, sourceRef);
        if (!gateFindings.isEmpty()) {
            saveFindings(task, gateFindings, codeContent, sourceRef, request.getLanguage(), ReviewContextHolder.getEvidence());
            recordAudit(task.getId(), "REVIEW_GATE_BLOCKED", "PREFLIGHT",
                    "Review stopped because Java preflight checks failed",
                    meta("sourceRef", sourceRef, "findingCount", gateFindings.size()));
            log.info("Java preflight blocked review: taskId={}, findingsCount={}", task.getId(), gateFindings.size());
            return;
        }
        ensureNotCancelled(task.getId());
        List<Finding> findings = executeReviewStrategy(codeContent, request.getLanguage(), request,
                task.getId(), sourceRef);
        ensureNotCancelled(task.getId());
        saveFindings(task, findings, codeContent, sourceRef, request.getLanguage(), ReviewContextHolder.getEvidence());
        log.info("审查完成: taskId={}, findingsCount={}", task.getId(), findings.size());
        } finally {
            ReviewContextHolder.clear();
        }
    }

    void performParallelReview(ReviewTask task, ReviewRequestDTO request) {
        List<Future<FileReviewResult>> futures;
        String scopeRef;

        ensureNotCancelled(task.getId());
        if (request.getFiles() != null && !request.getFiles().isEmpty()) {
            log.info("使用上传的文件列表进行审查: taskId={}, count={}", task.getId(), request.getFiles().size());
            scopeRef = "uploaded-files";
            List<Finding> gateFindings = javaReviewGateService.checkUploadedFiles(request.getFiles());
            if (!gateFindings.isEmpty()) {
                saveFindings(task, gateFindings, "", scopeRef, request.getLanguage(), List.of());
                recordAudit(task.getId(), "REVIEW_GATE_BLOCKED", "PREFLIGHT",
                        "Review stopped because uploaded Java files failed compilation",
                        meta("sourceRef", scopeRef, "findingCount", gateFindings.size()));
                log.info("Java preflight blocked uploaded-file review: taskId={}, findingsCount={}", task.getId(), gateFindings.size());
                return;
            }
            recordAudit(task.getId(), "REVIEW_SCOPE_SCANNED", "DISCOVERY",
                    "Uploaded file list resolved for review",
                    meta(
                            "source", "UPLOAD",
                            "sourceRef", scopeRef,
                            "fileCount", request.getFiles().size()
                    ));
            futures = request.getFiles().stream()
                    .map(file -> executor.submit(() -> reviewSingleFile(task.getId(), file.getPath(), file.getContent(), request)))
                    .collect(Collectors.toList());
        } else {
            String path = request.getProjectPath();
            if (path == null || path.trim().isEmpty()) {
                path = request.getDirectoryPath();
            }

            if (path == null || path.trim().isEmpty()) {
                throw new IllegalArgumentException("项目或目录路径不能为空");
            }

            ensureNotCancelled(task.getId());
            String includePaths = firstNonBlank(request.getIncludePaths(), configService.getSettings().getIncludePaths());
            String excludePaths = firstNonBlank(request.getExcludePaths(), configService.getSettings().getExcludePaths());

            List<Finding> gateFindings = javaReviewGateService.checkProjectScope(Paths.get(path), request, gitService);
            if (!gateFindings.isEmpty()) {
                saveFindings(task, gateFindings, "", path, null, List.of());
                recordAudit(task.getId(), "REVIEW_GATE_BLOCKED", "PREFLIGHT",
                        "Review stopped because project preflight checks failed",
                        meta("sourceRef", path, "findingCount", gateFindings.size()));
                log.info("Java preflight blocked project review: taskId={}, findingsCount={}", task.getId(), gateFindings.size());
                return;
            }

            ensureNotCancelled(task.getId());
            List<Path> files = codeParserService.scanDirectory(path, includePaths, excludePaths);
            files = ReviewContentResolver.filterChangedFiles(path, files, request);
            log.info("开始并行审查本地目录: taskId={}, path={}, 文件数={}", task.getId(), path, files.size());

            scopeRef = path;
            recordAudit(task.getId(), "REVIEW_SCOPE_SCANNED", "DISCOVERY",
                    "Local review scope scanned",
                    meta(
                            "source", "LOCAL_DIRECTORY",
                            "sourceRef", scopeRef,
                            "includePaths", includePaths,
                            "excludePaths", excludePaths,
                            "diffOnly", request.getDiffOnly(),
                            "changedFileCount", request.getChangedFiles() != null ? request.getChangedFiles().size() : 0,
                            "fileCount", files.size()
                    ));

            final Path rootPath = Paths.get(path).toAbsolutePath().normalize();

            futures = files.stream()
                    .map(filePath -> executor.submit(() -> {
                        try {
                            ensureNotCancelled(task.getId());
                            String content = codeParserService.readFile(filePath.toString());
                            ensureNotCancelled(task.getId());
                            String relativePath = rootPath.relativize(filePath.toAbsolutePath().normalize()).toString();
                            return reviewSingleFile(task.getId(), relativePath, content, request);
                        } catch (Exception e) {
                            if (e instanceof CancellationException) {
                                throw e;
                            }
                            log.error("读取文件失败: {}", filePath, e);
                            recordAudit(task.getId(), "FILE_REVIEW_FAILED", "ANALYSIS",
                                    "File review failed before strategy execution",
                                    meta(
                                            "sourceRef", filePath.toString(),
                                            "error", e.getMessage(),
                                            "errorType", e.getClass().getName()
                                    ));
                            return FileReviewResult.empty(filePath.toString());
                        }
                    }))
                    .collect(Collectors.toList());
        }

        if (futures.isEmpty()) {
            recordAudit(task.getId(), "NO_ANALYZABLE_FILES", "DISCOVERY",
                    "Review scope scan found no analyzable files",
                    meta("sourceRef", scopeRef, "fileCount", 0));
            saveFindings(task, List.of(), "", scopeRef, null, List.of());
        }

        List<Finding> allFindings = new ArrayList<>();
        try {
            for (Future<FileReviewResult> future : futures) {
                ensureNotCancelled(task.getId());
                try {
                    FileReviewResult result = future.get();
                    if (result != null && result.getFindings() != null) {
                        ensureNotCancelled(task.getId());
                        allFindings.addAll(result.getFindings());
                        saveFindings(task, result.getFindings(), result.getContent(), result.getSourceRef(),
                                result.getLanguage(), result.getEvidenceDrafts());
                    }
                } catch (CancellationException e) {
                    cancelFutures(futures);
                    throw e;
                } catch (Exception e) {
                    if (isCancelled(task.getId())) {
                        cancelFutures(futures);
                        throw new CancellationException("审查任务已取消");
                    }
                    log.error("获取审查结果失败", e);
                }
            }
        } catch (CancellationException e) {
            cancelFutures(futures);
            throw e;
        }

        recordAudit(task.getId(), "REVIEW_BATCH_COMPLETED", "ANALYSIS",
                "Parallel review batch completed",
                meta(
                        "sourceRef", scopeRef,
                        "fileCount", futures.size(),
                        "findingCount", allFindings.size()
                ));
        log.info("并行审查完成: taskId={}, findingsCount={}", task.getId(), allFindings.size());
    }

    private FileReviewResult reviewSingleFile(Long taskId, String relativePath, String content, ReviewRequestDTO request) {
        try {
            ensureNotCancelled(taskId);
            String language = ReviewContentResolver.detectLanguage(relativePath);
            List<Finding> fileFindings = executeReviewStrategy(content, language, request, taskId, relativePath);
            ensureNotCancelled(taskId);

            fileFindings.forEach(f -> f.setLocation(relativePath + ": " + f.getLocation()));

            List<EvidenceDraft> evidence = new ArrayList<>(ReviewContextHolder.getEvidence());
            ReviewContextHolder.clear();
            return FileReviewResult.builder()
                    .sourceRef(relativePath)
                    .language(language)
                    .content(content)
                    .findings(fileFindings)
                    .evidenceDrafts(evidence)
                    .build();
        } catch (Exception e) {
            if (e instanceof CancellationException) {
                ReviewContextHolder.clear();
                throw e;
            }
            log.error("文件审查失败: {}", relativePath, e);
            ReviewContextHolder.clear();
            return FileReviewResult.empty(relativePath);
        }
    }

    private void saveFindings(ReviewTask task, List<Finding> findings, String codeContent,
                              String sourceRef, String language, List<EvidenceDraft> evidenceDrafts) {
        if (findings == null) {
            return;
        }
        List<EvidenceDraft> taskEvidence = evidenceDrafts != null ? evidenceDrafts : List.of();
        if (!taskEvidence.isEmpty()) {
            provenanceService.persistTaskEvidence(task, taskEvidence);
        }
        int findingScopedEvidenceCount = 0;
        for (Finding finding : findings) {
            finding.setTaskId(task.getId());
            Finding saved = findingRepository.save(finding);
            provenanceService.groundFindingWithSource(task, saved, sourceRef, language, codeContent);
            List<EvidenceDraft> findingEvidence = retrieveFindingScopedEvidence(saved, sourceRef, language, codeContent);
            findingScopedEvidenceCount += provenanceService
                    .attachContextEvidenceToFinding(task, saved, findingEvidence)
                    .size();
        }
        auditService.record(task.getId(), "FINDINGS_SAVED", "PERSISTENCE", "system",
                "Review findings and evidence saved",
                meta("sourceRef", sourceRef, "language", language, "findingCount", findings.size(),
                        "taskEvidenceCount", taskEvidence.size(),
                        "findingScopedEvidenceCount", findingScopedEvidenceCount));
    }

    private List<EvidenceDraft> retrieveFindingScopedEvidence(Finding finding,
                                                              String sourceRef,
                                                              String language,
                                                              String codeContent) {
        if (findingRagEvidenceService == null) {
            return List.of();
        }
        return findingRagEvidenceService.retrieveForFinding(finding, sourceRef, language, codeContent);
    }

    private void recordAudit(Long taskId, String eventType, String stage, String message, Map<String, Object> metadata) {
        if (taskId == null || auditService == null) {
            return;
        }
        auditService.record(taskId, eventType, stage, "system", message, metadata);
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    private void ensureNotCancelled(Long taskId) {
        if (isCancelled(taskId)) {
            throw new CancellationException("审查任务已取消");
        }
    }

    private boolean isCancelled(Long taskId) {
        return Thread.currentThread().isInterrupted()
                || (taskId != null && cancellationRequested.test(taskId));
    }

    private void cancelFutures(List<? extends Future<?>> futures) {
        if (futures == null) {
            return;
        }
        futures.forEach(future -> {
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        });
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

    @lombok.Data
    @lombok.Builder
    private static class FileReviewResult {
        private String sourceRef;
        private String language;
        private String content;
        private List<Finding> findings;
        private List<EvidenceDraft> evidenceDrafts;

        static FileReviewResult empty(String sourceRef) {
            return FileReviewResult.builder()
                    .sourceRef(sourceRef)
                    .content("")
                    .findings(List.of())
                    .evidenceDrafts(List.of())
                    .build();
        }
    }
}
