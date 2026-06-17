package com.codeguardian.service;

import com.codeguardian.dto.FileContentDTO;
import com.codeguardian.dto.ReviewRequestDTO;
import com.codeguardian.dto.ReviewResponseDTO;
import com.codeguardian.entity.Finding;
import com.codeguardian.entity.ReviewTask;
import com.codeguardian.repository.FindingRepository;
import com.codeguardian.repository.ReviewTaskRepository;
import com.codeguardian.service.cache.SemanticFingerprintCacheService;
import com.codeguardian.service.agent.ReviewAgentOrchestrator;
import com.codeguardian.service.provenance.ReviewAuditService;
import com.codeguardian.service.provenance.ReviewProvenanceService;
import com.codeguardian.service.rag.FindingRagEvidenceService;
import com.codeguardian.service.rag.TaskRagPackService;
import com.codeguardian.service.rules.RuleEngineService;
import com.codeguardian.enums.ReviewTypeEnum;
import com.codeguardian.enums.SeverityEnum;
import com.codeguardian.enums.TaskStatusEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 代码审查服务
 */
@Service
@Slf4j
public class ReviewService {
    
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
    private final JavaReviewGateService javaReviewGateService = new JavaReviewGateService();
    @Autowired(required = false)
    @Lazy
    private TaskRagPackService taskRagPackService;
    
    private final ExecutorService executor = Executors.newFixedThreadPool(20);
    private final ExecutorService orchestrationExecutor = Executors.newCachedThreadPool();
    private final Map<Long, Future<?>> runningTasks = new ConcurrentHashMap<>();
    private final Set<Long> cancelRequestedTasks = ConcurrentHashMap.newKeySet();

    @Autowired
    public ReviewService(ReviewTaskRepository taskRepository,
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
                         FindingRagEvidenceService findingRagEvidenceService) {
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
    }

    ReviewService(ReviewTaskRepository taskRepository,
                  FindingRepository findingRepository,
                  AIModelService aiModelService,
                  CodeParserService codeParserService,
                  RuleEngineService ruleEngineService,
                  SystemConfigService configService,
                  GitService gitService,
                  SemanticFingerprintCacheService fingerprintCacheService,
                  ReviewAgentOrchestrator reviewAgentOrchestrator,
                  ReviewAuditService auditService,
                  ReviewProvenanceService provenanceService) {
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
                null);
    }
    
    @jakarta.annotation.PreDestroy
    public void destroy() {
        log.info("Closing review service executors...");
        runningTasks.values().forEach(future -> {
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        });
        executor.shutdown();
        orchestrationExecutor.shutdown();
    }
    
    /**
     * 创建审查任务并开始审查
     */
    @Transactional
    public ReviewResponseDTO createReviewTask(ReviewRequestDTO request) {
        log.info("创建审查任务: type={}, scope={}", request.getReviewType(), 
                request.getProjectPath() != null ? request.getProjectPath() : 
                request.getFilePath() != null ? request.getFilePath() : "代码片段");
        
        // 创建任务
        ReviewTask task = ReviewTask.builder()
                .name(request.getTaskName() != null ? request.getTaskName() : 
                      generateTaskName(request))
                .reviewType(ReviewTypeEnum.fromName(request.getReviewType()).getValue())
                .scope(determineScope(request))
                .status(TaskStatusEnum.RUNNING.getValue())
                .createdAt(LocalDateTime.now())
                .build();
        
        task = taskRepository.save(task);
        try {
            String type = request.getReviewType() != null ? request.getReviewType().toUpperCase() : "";
            if ("SNIPPET".equals(type)) {
                if (request.getCodeSnippet() != null && !request.getCodeSnippet().isBlank()) {
                    task.setScope(request.getCodeSnippet());
                    task = taskRepository.save(task);
                }
            } else if ("FILE".equals(type)) {
                if (request.getFiles() != null && !request.getFiles().isEmpty()) {
                    java.nio.file.Path root = persistUploadedFiles(task.getId(), request.getFiles());
                    java.nio.file.Path out = resolveUploadedPath(root, request.getFiles().get(0));
                    if (out != null) {
                        task.setScope(out.toString());
                        task = taskRepository.save(task);
                    }
                } else if (request.getFilePath() != null && !request.getFilePath().isBlank()) {
                    task.setScope(request.getFilePath());
                    task = taskRepository.save(task);
                }
            } else if ("DIRECTORY".equals(type)) {
                if (request.getFiles() != null && !request.getFiles().isEmpty()) {
                    java.nio.file.Path root = persistUploadedFiles(task.getId(), request.getFiles());
                    task.setScope(root.toString());
                    task = taskRepository.save(task);
                } else if (request.getDirectoryPath() != null && !request.getDirectoryPath().isBlank()) {
                    task.setScope(request.getDirectoryPath());
                    task = taskRepository.save(task);
                }
            } else if ("PROJECT".equals(type)) {
                if (request.getFiles() != null && !request.getFiles().isEmpty()) {
                    java.nio.file.Path root = persistUploadedFiles(task.getId(), request.getFiles());
                    task.setScope(root.toString());
                    task = taskRepository.save(task);
                } else if (request.getProjectPath() != null && !request.getProjectPath().isBlank()) {
                    task.setScope(request.getProjectPath());
                    task = taskRepository.save(task);
                }
            } else if ("GIT".equals(type)) {
                if (request.getGitUrl() != null && !request.getGitUrl().isBlank()) {
                    task.setScope(request.getGitUrl());
                    task = taskRepository.save(task);
                } else if (request.getProjectPath() != null && !request.getProjectPath().isBlank()) {
                    task.setScope(request.getProjectPath());
                    task = taskRepository.save(task);
                }
            }
        } catch (Exception e) {
            log.warn("保存代码样本失败，将使用默认范围标签", e);
        }
        auditService.record(task.getId(), "TASK_CREATED", "SUBMIT", "system",
                "Review task created and queued",
                meta(
                        "reviewType", request.getReviewType(),
                        "scope", task.getScope(),
                        "modelProvider", request.getModelProvider(),
                        "enableRag", request.getEnableRag(),
                        "agentMode", request.getAgentMode(),
                        "rulesOnly", request.getRulesOnly()
                ));

        ReviewTask finalTask = task;
        Long taskId = task.getId();
        Runnable reviewJob = () -> runReviewAsync(taskId, finalTask, request);

        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submitReviewJob(taskId, reviewJob);
                }
            });
        } else {
            submitReviewJob(taskId, reviewJob);
        }
        
        return buildResponseDTO(finalTask);
    }
    
    private void runReviewAsync(Long taskId, ReviewTask fallbackTask, ReviewRequestDTO request) {
        if (taskId == null) {
            log.error("审查任务执行失败: taskId 为空");
            return;
        }

        ReviewTask task = taskRepository.findById(taskId).orElse(null);
        if (task == null) {
            task = fallbackTask;
        }
        if (task == null || task.getId() == null || !taskId.equals(task.getId())) {
            log.error("审查任务执行失败: taskId={} 不存在", taskId);
            return;
        }

        try {
            ensureTaskNotCancelled(task.getId());
            auditService.record(task.getId(), "TASK_STARTED", "ORCHESTRATION", "system",
                    "Review task execution started",
                    meta("reviewType", request.getReviewType(), "scope", task.getScope()));
            prepareTaskRagPack(task, request);
            ensureTaskNotCancelled(task.getId());
            executionCoordinator().performReview(task, request);
            ensureTaskNotCancelled(task.getId());
            evictTaskRagPackBeforeTerminal(task.getId(), request);
            task.setStatus(TaskStatusEnum.COMPLETED.getValue());
            task.setCompletedAt(LocalDateTime.now());
            auditService.record(task.getId(), "TASK_COMPLETED", "ORCHESTRATION", "system",
                    "Review task execution completed",
                    meta("completedAt", task.getCompletedAt()));
        } catch (CancellationException e) {
            log.info("审查任务已取消: taskId={}", task.getId());
            evictTaskRagPackBeforeTerminal(task.getId(), request);
            markTaskCancelled(task, e.getMessage());
        } catch (Exception e) {
            if (isCancellationRequested(task.getId())) {
                evictTaskRagPackBeforeTerminal(task.getId(), request);
                log.info("审查任务在异常后确认为已取消: taskId={}, error={}", task.getId(), e.getMessage());
                markTaskCancelled(task, "审查任务已取消");
            } else {
                log.error("审查任务执行失败: taskId={}", task.getId(), e);
                evictTaskRagPackBeforeTerminal(task.getId(), request);
                task.setStatus(TaskStatusEnum.FAILED.getValue());
                task.setErrorMessage(e.getMessage());
                auditService.record(task.getId(), "TASK_FAILED", "ORCHESTRATION", "system",
                        "Review task execution failed",
                        meta("error", e.getMessage(), "errorType", e.getClass().getName()));
            }
        } finally {
            taskRepository.save(task);
        }
    }

    private void submitReviewJob(Long taskId, Runnable reviewJob) {
        FutureTask<Void> futureTask = new FutureTask<>(() -> {
            try {
                reviewJob.run();
            } finally {
                runningTasks.remove(taskId);
                cancelRequestedTasks.remove(taskId);
            }
            return null;
        });
        runningTasks.put(taskId, futureTask);
        orchestrationExecutor.submit(futureTask);
    }

    private void ensureTaskNotCancelled(Long taskId) {
        if (isCancellationRequested(taskId)) {
            throw new CancellationException("审查任务已取消");
        }
    }

    private boolean isCancellationRequested(Long taskId) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        if (taskId == null) {
            return false;
        }
        if (cancelRequestedTasks.contains(taskId)) {
            return true;
        }
        return taskRepository.findById(taskId)
                .map(task -> TaskStatusEnum.CANCELLED.getValue().equals(task.getStatus()))
                .orElse(false);
    }

    private void markTaskCancelled(ReviewTask task, String reason) {
        if (task == null || task.getId() == null) {
            return;
        }
        boolean alreadyCancelled = taskRepository.findById(task.getId())
                .map(current -> TaskStatusEnum.CANCELLED.getValue().equals(current.getStatus()))
                .orElse(false);
        task.setStatus(TaskStatusEnum.CANCELLED.getValue());
        task.setCompletedAt(LocalDateTime.now());
        task.setErrorMessage(reason != null ? reason : "审查任务已取消");
        if (!alreadyCancelled) {
            auditService.record(task.getId(), "TASK_CANCELLED", "ORCHESTRATION", "system",
                    "Review task cancelled",
                    meta("reason", task.getErrorMessage(), "cancelledAt", task.getCompletedAt()));
        }
    }

    private java.nio.file.Path persistUploadedFiles(Long taskId, List<FileContentDTO> files) throws java.io.IOException {
        java.nio.file.Path root = java.nio.file.Paths.get(
                System.getProperty("java.io.tmpdir"),
                "codeguardian",
                "uploads",
                String.valueOf(taskId)
        ).toAbsolutePath().normalize();
        java.nio.file.Files.createDirectories(root);
        if (files == null) {
            return root;
        }
        for (FileContentDTO file : files) {
            java.nio.file.Path out = resolveUploadedPath(root, file);
            if (out == null) {
                continue;
            }
            java.nio.file.Files.createDirectories(out.getParent());
            java.nio.file.Files.writeString(out, file.getContent() != null ? file.getContent() : "");
        }
        return root;
    }

    private java.nio.file.Path resolveUploadedPath(java.nio.file.Path root, FileContentDTO file) {
        if (root == null || file == null) {
            return null;
        }
        String rawPath = file.getPath() != null && !file.getPath().isBlank()
                ? file.getPath()
                : "uploaded.txt";
        String rel = rawPath.replace('\\', '/').replaceFirst("^/+", "");
        java.nio.file.Path out = root.resolve(rel).normalize();
        if (!out.startsWith(root)) {
            throw new IllegalArgumentException("非法的上传文件路径: " + rawPath);
        }
        return out;
    }

    private ReviewRequestDTO rebuildRetryRequest(ReviewTask task) {
        ReviewTypeEnum reviewType = ReviewTypeEnum.fromValue(task.getReviewType());
        String scope = task.getScope();
        if (scope == null || scope.isBlank() || isPlaceholderScope(scope)) {
            throw new IllegalStateException("该任务缺少可重试的审查范围");
        }

        ReviewRequestDTO request = ReviewRequestDTO.builder()
                .reviewType(reviewType.name())
                .taskName(task.getName() + "-重试")
                .language("Java")
                .enableRag(true)
                .rulesOnly(false)
                .build();

        switch (reviewType) {
            case SNIPPET -> request.setCodeSnippet(scope);
            case FILE -> request.setFilePath(scope);
            case DIRECTORY -> request.setDirectoryPath(scope);
            case PROJECT -> request.setProjectPath(scope);
            case GIT -> {
                try {
                    java.nio.file.Path path = java.nio.file.Paths.get(scope);
                    if (java.nio.file.Files.isDirectory(path)) {
                        request.setProjectPath(scope);
                    } else {
                        request.setGitUrl(scope);
                    }
                } catch (Exception e) {
                    request.setGitUrl(scope);
                }
            }
            default -> throw new IllegalStateException("不支持重试的审查类型: " + reviewType.name());
        }
        return request;
    }

    private boolean isPlaceholderScope(String scope) {
        return "代码片段".equals(scope)
                || "指定文件".equals(scope)
                || "指定目录".equals(scope)
                || "整个项目".equals(scope)
                || "git项目".equalsIgnoreCase(scope);
    }

    private void prepareTaskRagPack(ReviewTask task, ReviewRequestDTO request) {
        if (taskRagPackService == null || Boolean.FALSE.equals(request.getEnableRag())) {
            return;
        }
        taskRagPackService.prepareAndStore(task, request);
    }

    private void evictTaskRagPack(Long taskId, ReviewRequestDTO request) {
        if (taskRagPackService == null || Boolean.FALSE.equals(request.getEnableRag())) {
            return;
        }
        taskRagPackService.evict(taskId);
    }

    private void evictTaskRagPackBeforeTerminal(Long taskId, ReviewRequestDTO request) {
        try {
            evictTaskRagPack(taskId, request);
        } catch (Exception e) {
            log.warn("Failed to evict task RAG pack before terminal audit event: taskId={}, error={}",
                    taskId, e.getMessage());
        }
    }

    private List<Finding> executeReviewStrategy(String codeContent, String language, ReviewRequestDTO request) {
        return executionCoordinator().executeReviewStrategy(codeContent, language, request);
    }

    private List<Finding> executeReviewStrategy(String codeContent, String language, ReviewRequestDTO request,
                                                Long taskId, String sourceRef) {
        return executionCoordinator().executeReviewStrategy(codeContent, language, request, taskId, sourceRef);
    }

    private void performParallelReview(ReviewTask task, ReviewRequestDTO request) {
        executionCoordinator().performParallelReview(task, request);
    }

    private ReviewExecutionCoordinator executionCoordinator() {
        return new ReviewExecutionCoordinator(
                taskRepository,
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
                this::isCancellationRequested
        );
    }
    public ReviewResponseDTO getReviewTask(Long taskId) {
        ReviewTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new RuntimeException("任务不存在: " + taskId));
        
        return buildResponseDTO(task);
    }

    @Transactional
    public ReviewResponseDTO cancelReviewTask(Long taskId) {
        ReviewTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new RuntimeException("任务不存在: " + taskId));
        TaskStatusEnum status = TaskStatusEnum.fromValue(task.getStatus());
        if (status == TaskStatusEnum.COMPLETED
                || status == TaskStatusEnum.FAILED
                || status == TaskStatusEnum.CANCELLED) {
            return buildResponseDTO(task);
        }

        cancelRequestedTasks.add(taskId);
        Future<?> runningTask = runningTasks.get(taskId);
        if (runningTask != null && !runningTask.isDone()) {
            runningTask.cancel(true);
        }

        markTaskCancelled(task, "用户取消审查任务");
        task = taskRepository.save(task);
        return buildResponseDTO(task);
    }

    @Transactional
    public ReviewResponseDTO retryReviewTask(Long taskId) {
        ReviewTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new RuntimeException("任务不存在: " + taskId));
        TaskStatusEnum status = TaskStatusEnum.fromValue(task.getStatus());
        if (status == TaskStatusEnum.RUNNING || status == TaskStatusEnum.PENDING) {
            throw new IllegalStateException("任务仍在运行，不能重试");
        }

        ReviewRequestDTO retryRequest = rebuildRetryRequest(task);
        auditService.record(task.getId(), "TASK_RETRY_REQUESTED", "SUBMIT", "system",
                "Review task retry requested",
                meta("sourceTaskId", task.getId(), "status", status.name()));
        ReviewResponseDTO response = createReviewTask(retryRequest);
        auditService.record(task.getId(), "TASK_RETRY_CREATED", "SUBMIT", "system",
                "Retry review task created",
                meta("sourceTaskId", task.getId(), "retryTaskId", response.getTaskId()));
        return response;
    }
    
    /**
     * 构建响应DTO
     */
    private ReviewResponseDTO buildResponseDTO(ReviewTask task) {
        List<Finding> findings = findingRepository.findByTaskId(task.getId());
        if (findings == null) findings = List.of();
        
        // 使用Stream API一次性统计各级别问题数量
        Map<Integer, Integer> severityCounts = findings.stream()
                .filter(f -> f.getSeverity() != null)
                .collect(Collectors.groupingBy(Finding::getSeverity, Collectors.summingInt(e -> 1)));

        TaskStatusEnum status = TaskStatusEnum.fromValue(task.getStatus());

        return ReviewResponseDTO.builder()
                .taskId(task.getId())
                .taskName(task.getName())
                .status(status.name())
                .statusLabel(status.getDesc())
                .errorMessage(task.getErrorMessage())
                .canCancel(status == TaskStatusEnum.PENDING || status == TaskStatusEnum.RUNNING)
                .canRetry(status == TaskStatusEnum.FAILED || status == TaskStatusEnum.CANCELLED)
                .reviewType(ReviewTypeEnum.fromValue(task.getReviewType()).name())
                .scope(task.getScope())
                .createdAt(task.getCreatedAt())
                .totalFindings(findings.size())
                .criticalCount(severityCounts.getOrDefault(SeverityEnum.CRITICAL.getValue(), 0))
                .highCount(severityCounts.getOrDefault(SeverityEnum.HIGH.getValue(), 0))
                .mediumCount(severityCounts.getOrDefault(SeverityEnum.MEDIUM.getValue(), 0))
                .lowCount(severityCounts.getOrDefault(SeverityEnum.LOW.getValue(), 0))
                .build();
    }
    
    /**
     * 生成任务名称
     *
     * <p>项目/目录/文件/Git 类型优先使用路径或仓库名，保证仪表盘展示清晰名称。</p>
     *
     * @param request 审查请求
     * @return 任务名称
     */
    private String generateTaskName(ReviewRequestDTO request) {
        return ReviewTaskDescriptor.generateTaskName(request);
    }

    public static String guessSnippetDisplayName(String code, String language) {
        return ReviewTaskDescriptor.guessSnippetDisplayName(code, language);
    }
    
    private String determineScope(ReviewRequestDTO request) {
        return ReviewTaskDescriptor.determineScope(request);
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
