package com.codeguardian.controller;

import com.codeguardian.dto.ReviewRequestDTO;
import com.codeguardian.dto.ReviewResponseDTO;
import com.codeguardian.dto.integration.CicdStatusResponse;
import com.codeguardian.dto.integration.CicdTaskConfig;
import com.codeguardian.dto.integration.CicdTriggerRequest;
import com.codeguardian.dto.integration.CodeGuardianProjectConfig;
import com.codeguardian.entity.Finding;
import com.codeguardian.entity.ReviewTask;
import com.codeguardian.enums.TaskStatusEnum;
import com.codeguardian.repository.FindingRepository;
import com.codeguardian.repository.ReviewTaskRepository;
import com.codeguardian.service.GitService;
import com.codeguardian.service.ReviewService;
import com.codeguardian.service.integration.CicdArtifactService;
import com.codeguardian.service.integration.CicdTaskConfigRegistry;
import com.codeguardian.service.integration.CodeGuardianConfigFileService;
import com.codeguardian.service.integration.GitFeedbackService;
import com.codeguardian.service.integration.PrDiffService;
import com.codeguardian.service.integration.PrInlineComment;
import com.codeguardian.service.integration.QualityGateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/cicd")
@RequiredArgsConstructor
@Slf4j
public class CicdController {

    private final ReviewService reviewService;
    private final ReviewTaskRepository taskRepository;
    private final FindingRepository findingRepository;
    private final QualityGateService qualityGateService;
    private final GitService gitService;
    private final GitFeedbackService gitFeedbackService;
    private final PrDiffService prDiffService;
    private final CodeGuardianConfigFileService configFileService;
    private final CicdTaskConfigRegistry taskConfigRegistry;
    private final CicdArtifactService artifactService;

    @PostMapping("/trigger")
    public ResponseEntity<CicdStatusResponse> triggerReview(@RequestBody CicdTriggerRequest request) {
        log.info("Received CI/CD review trigger: gitUrl={}, branch={}, commitHash={}, projectPath={}, diffOnly={}",
                request.getGitUrl(), request.getBranch(), request.getCommitHash(), request.getProjectPath(),
                request.getDiffOnly());

        String localPath = request.getProjectPath();
        if ((localPath == null || localPath.isBlank()) && request.getGitUrl() != null && !request.getGitUrl().isBlank()) {
            localPath = gitService.cloneRepository(request.getGitUrl(), null, null);
        }
        if (localPath == null || localPath.isBlank()) {
            return ResponseEntity.badRequest().body(CicdStatusResponse.builder()
                    .status(TaskStatusEnum.FAILED.name())
                    .passed(false)
                    .message("gitUrl or projectPath is required")
                    .build());
        }

        String checkoutTarget = firstNonBlank(request.getHeadCommit(), request.getCommitHash());
        gitService.checkoutRevision(localPath, request.getBranch(), checkoutTarget);

        CodeGuardianProjectConfig projectConfig = configFileService.load(Path.of(localPath));
        String includePaths = firstNonBlank(request.getIncludePaths(), configFileService.toPathConfig(projectConfig.getIncludePaths()));
        String excludePaths = firstNonBlank(request.getExcludePaths(), configFileService.toPathConfig(projectConfig.getExcludePaths()));
        String blockOn = firstNonBlank(request.getBlockOn(), firstNonBlank(projectConfig.getBlockOn(), "CRITICAL"));
        Boolean diffOnly = firstNonNull(request.getDiffOnly(), projectConfig.getDiffOnly(), Boolean.FALSE);
        Boolean enableRag = firstNonNull(request.getEnableRag(), ragModeEnabled(projectConfig.getRagMode()), Boolean.TRUE);
        Boolean rulesOnly = firstNonNull(request.getRulesOnly(), Boolean.FALSE);
        String headCommit = firstNonBlank(request.getHeadCommit(), firstNonBlank(request.getCommitHash(), "HEAD"));

        List<String> changedFiles = request.getChangedFiles() != null ? request.getChangedFiles() : List.of();
        if (Boolean.TRUE.equals(diffOnly) && changedFiles.isEmpty()
                && request.getBaseCommit() != null && !request.getBaseCommit().isBlank()) {
            changedFiles = gitService.getChangedFiles(localPath, request.getBaseCommit(), headCommit);
        }
        String unifiedDiff = "";
        if (Boolean.TRUE.equals(diffOnly) && request.getBaseCommit() != null && !request.getBaseCommit().isBlank()) {
            unifiedDiff = gitService.getUnifiedDiff(localPath, request.getBaseCommit(), headCommit);
        }

        ReviewRequestDTO reviewRequest = ReviewRequestDTO.builder()
                .reviewType("GIT")
                .gitUrl(request.getGitUrl())
                .projectPath(localPath)
                .taskName("CI-" + firstNonBlank(request.getTriggerBy(), "AUTO") + "-" + System.currentTimeMillis())
                .includePaths(includePaths)
                .excludePaths(excludePaths)
                .diffOnly(diffOnly)
                .baseCommit(request.getBaseCommit())
                .headCommit(headCommit)
                .changedFiles(changedFiles)
                .enableRag(enableRag)
                .rulesOnly(rulesOnly)
                .ruleTemplate(request.getRuleTemplate())
                .build();

        ReviewResponseDTO taskResponse = reviewService.createReviewTask(reviewRequest);
        taskConfigRegistry.put(CicdTaskConfig.builder()
                .taskId(taskResponse.getTaskId())
                .blockOn(blockOn)
                .gitUrl(request.getGitUrl())
                .prNumber(request.getPrNumber())
                .diffOnly(diffOnly)
                .inlineComments(request.getInlineComments())
                .changedFiles(changedFiles)
                .includePaths(includePaths)
                .excludePaths(excludePaths)
                .baseCommit(request.getBaseCommit())
                .headCommit(headCommit)
                .unifiedDiff(unifiedDiff)
                .inlineCommentsPosted(false)
                .configSource(projectConfig.getSourcePath())
                .build());

        return ResponseEntity.ok(CicdStatusResponse.builder()
                .taskId(taskResponse.getTaskId())
                .status(TaskStatusEnum.RUNNING.name())
                .passed(true)
                .message("CI review task submitted")
                .reportUrl("/report/" + taskResponse.getTaskId())
                .build());
    }

    @GetMapping("/status/{taskId}")
    public ResponseEntity<CicdStatusResponse> checkStatus(
            @PathVariable Long taskId,
            @RequestParam(required = false) String blockOn) {

        Optional<ReviewTask> taskOpt = taskRepository.findById(taskId);
        if (taskOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        ReviewTask task = taskOpt.get();
        TaskStatusEnum status = TaskStatusEnum.fromValue(task.getStatus());
        boolean isCompleted = TaskStatusEnum.COMPLETED.getValue().equals(task.getStatus());
        boolean isFailed = TaskStatusEnum.FAILED.getValue().equals(task.getStatus());
        String effectiveBlockOn = effectiveBlockOn(taskId, blockOn);

        if (isCompleted) {
            QualityGateService.QualityGateResult gateResult = qualityGateService.evaluateQualityGate(taskId, effectiveBlockOn);
            publishInlineCommentsIfNeeded(taskId);
            ReviewResponseDTO dto = reviewService.getReviewTask(taskId);
            CicdStatusResponse.Summary summary = CicdStatusResponse.Summary.builder()
                    .critical(safeInt(dto.getCriticalCount()))
                    .high(safeInt(dto.getHighCount()))
                    .medium(safeInt(dto.getMediumCount()))
                    .low(safeInt(dto.getLowCount()))
                    .build();
            boolean passed = gateResult.isPassed();
            return ResponseEntity.ok(CicdStatusResponse.builder()
                    .taskId(task.getId())
                    .status(status.name())
                    .passed(passed)
                    .message(passed ? "审查通过" : buildGateFailureMessage(effectiveBlockOn, gateResult))
                    .reportUrl("/report/" + task.getId())
                    .summary(summary)
                    .qualityGate(toGateResponse(gateResult))
                    .build());
        }

        return ResponseEntity.ok(CicdStatusResponse.builder()
                .taskId(task.getId())
                .status(status.name())
                .passed(!isFailed)
                .message(isFailed ? "审查任务执行失败: " + task.getErrorMessage() : "审查进行中")
                .reportUrl("/report/" + task.getId())
                .build());
    }

    @GetMapping("/result/{taskId}")
    public ResponseEntity<Map<String, Object>> getResult(
            @PathVariable Long taskId,
            @RequestParam(required = false) String blockOn) {
        return ResponseEntity.ok(artifactService.buildResult(taskId, effectiveBlockOn(taskId, blockOn)));
    }

    @GetMapping(value = "/sarif/{taskId}", produces = "application/sarif+json")
    public ResponseEntity<Map<String, Object>> getSarif(@PathVariable Long taskId) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/sarif+json"))
                .body(artifactService.buildSarif(taskId));
    }

    private String effectiveBlockOn(Long taskId, String blockOn) {
        if (blockOn != null && !blockOn.isBlank()) {
            return blockOn;
        }
        return taskConfigRegistry.get(taskId)
                .map(CicdTaskConfig::getBlockOn)
                .filter(value -> value != null && !value.isBlank())
                .orElse("CRITICAL");
    }

    private String buildGateFailureMessage(String blockOn, QualityGateService.QualityGateResult gateResult) {
        if (gateResult.isSeverityBlocked() && gateResult.isProvenanceBlocked() && gateResult.isIntegrityBlocked()) {
            return "审查未通过：存在 " + blockOn + " 级别及以上的问题，且高风险问题未满足溯源策略，证明包/审计链校验失败";
        }
        if (gateResult.isSeverityBlocked() && gateResult.isProvenanceBlocked()) {
            return "审查未通过：存在 " + blockOn + " 级别及以上的问题，且高风险问题未满足溯源策略";
        }
        if (gateResult.isSeverityBlocked() && gateResult.isIntegrityBlocked()) {
            return "审查未通过：存在 " + blockOn + " 级别及以上的问题，且证明包/审计链校验失败";
        }
        if (gateResult.isProvenanceBlocked() && gateResult.isIntegrityBlocked()) {
            return "审查未通过：高风险问题未满足溯源策略，且证明包/审计链校验失败";
        }
        if (gateResult.isSeverityBlocked()) {
            return "审查未通过：存在 " + blockOn + " 级别及以上的问题";
        }
        if (gateResult.isProvenanceBlocked()) {
            return "审查未通过：存在未满足溯源策略的高风险/严重问题";
        }
        if (gateResult.isIntegrityBlocked()) {
            return "审查未通过：审查依据、证明包或审计链校验失败";
        }
        return "审查未通过：质量门禁未通过";
    }

    private CicdStatusResponse.QualityGate toGateResponse(QualityGateService.QualityGateResult result) {
        return CicdStatusResponse.QualityGate.builder()
                .reason(result.getReason())
                .severityBlocked(result.isSeverityBlocked())
                .provenanceBlocked(result.isProvenanceBlocked())
                .integrityBlocked(result.isIntegrityBlocked())
                .ungroundedCritical(result.getUngroundedCriticalCount())
                .ungroundedHigh(result.getUngroundedHighCount())
                .ungroundedRisk(result.getUngroundedRiskCount())
                .groundingMinSeverity(result.getGroundingMinSeverity())
                .groundingPolicyValid(result.getGroundingPolicyValid())
                .groundingPolicyReason(result.getGroundingPolicyReason())
                .groundingViolationCount(result.getGroundingViolationCount())
                .missingSourceEvidenceCount(result.getMissingSourceEvidenceCount())
                .evidenceCountMismatchCount(result.getEvidenceCountMismatchCount())
                .evidenceHashMismatchCount(result.getEvidenceHashMismatchCount())
                .invalidEvidenceContentHashCount(result.getInvalidEvidenceContentHashCount())
                .invalidSourceAnchorCount(result.getInvalidSourceAnchorCount())
                .proofBundleValid(result.getProofBundleValid())
                .proofBundleReason(result.getProofBundleReason())
                .auditChainValid(result.getAuditChainValid())
                .auditSignatureValid(result.getAuditSignatureValid())
                .auditCoverageValid(result.getAuditCoverageValid())
                .auditOrderValid(result.getAuditOrderValid())
                .evidenceHashesValid(result.getEvidenceHashesValid())
                .reviewStateHashValid(result.getReviewStateHashValid())
                .runtimeManifestHashValid(result.getRuntimeManifestHashValid())
                .bundleHashValid(result.getBundleHashValid())
                .bundleSignatureValid(result.getBundleSignatureValid())
                .currentStateMatch(result.getCurrentStateMatch())
                .currentReviewStateMatch(result.getCurrentReviewStateMatch())
                .currentRuntimeManifestMatch(result.getCurrentRuntimeManifestMatch())
                .currentBundleMatch(result.getCurrentBundleMatch())
                .runtimeGuardValid(result.getRuntimeGuardValid())
                .dbGuardQuerySupported(result.getDbGuardQuerySupported())
                .dbAppendOnlyGuardsInstalled(result.getDbAppendOnlyGuardsInstalled())
                .dbGuardUpdatesBlocked(result.getDbGuardUpdatesBlocked())
                .dbGuardDeletesBlocked(result.getDbGuardDeletesBlocked())
                .dbGuardReason(result.getDbGuardReason())
                .build();
    }

    private void publishInlineCommentsIfNeeded(Long taskId) {
        taskConfigRegistry.get(taskId).ifPresent(config -> {
            if (!Boolean.TRUE.equals(config.getInlineComments())
                    || Boolean.TRUE.equals(config.getInlineCommentsPosted())
                    || config.getGitUrl() == null || config.getGitUrl().isBlank()
                    || config.getPrNumber() == null || config.getPrNumber().isBlank()) {
                return;
            }
            List<Finding> findings = findingRepository.findByTaskId(taskId);
            List<PrInlineComment> comments = prDiffService.buildInlineComments(findings, config.getUnifiedDiff());
            gitFeedbackService.postInlineComments(
                    config.getGitUrl(),
                    config.getPrNumber(),
                    config.getBaseCommit(),
                    config.getHeadCommit(),
                    config.getBaseCommit(),
                    comments);
            config.setInlineCommentsPosted(true);
        });
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String firstNonBlank(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    private Boolean ragModeEnabled(String ragMode) {
        if (ragMode == null || ragMode.isBlank()) {
            return null;
        }
        String normalized = ragMode.trim().toLowerCase();
        return !normalized.equals("off")
                && !normalized.equals("none")
                && !normalized.equals("false")
                && !normalized.equals("disabled");
    }

    private int safeInt(Integer value) {
        return value != null ? value : 0;
    }
}
