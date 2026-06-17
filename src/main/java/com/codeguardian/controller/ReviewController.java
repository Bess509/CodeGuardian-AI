package com.codeguardian.controller;

import com.codeguardian.dto.FindingDTO;
import com.codeguardian.dto.GitCloneResponseDTO;
import com.codeguardian.dto.GitFileResponseDTO;
import com.codeguardian.dto.ReviewRequestDTO;
import com.codeguardian.dto.ReviewResponseDTO;
import com.codeguardian.entity.Finding;
import com.codeguardian.entity.ReviewTask;
import com.codeguardian.repository.FindingRepository;
import com.codeguardian.repository.ReviewAuditEventRepository;
import com.codeguardian.repository.ReviewEvidenceRepository;
import com.codeguardian.repository.ReviewTaskRepository;
import com.codeguardian.service.ReviewService;
import com.codeguardian.service.GitService;
import com.codeguardian.service.SystemConfigService;
import com.codeguardian.enums.ReviewTypeEnum;
import com.codeguardian.enums.SeverityEnum;
import com.codeguardian.enums.TaskStatusEnum;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import cn.dev33.satoken.annotation.SaCheckPermission;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 代码审查控制器
 */
@RestController
@RequestMapping("/api/review")
@RequiredArgsConstructor
@Slf4j
public class ReviewController {
    
    private final ReviewService reviewService;
    private final ReviewTaskRepository taskRepository;
    private final FindingRepository findingRepository;
    private final ReviewEvidenceRepository evidenceRepository;
    private final ReviewAuditEventRepository auditEventRepository;
    private final GitService gitService;
    private final SystemConfigService configService;
    
    /**
     * 审查代码片段
     *
     * <p>需`REVIEW`权限。</p>
     */
    @PostMapping("/snippet")
    @SaCheckPermission("REVIEW")
    public ResponseEntity<ReviewResponseDTO> reviewSnippet(@Valid @RequestBody ReviewRequestDTO request) {
        request.setReviewType("SNIPPET");
        ReviewResponseDTO response = reviewService.createReviewTask(request);
        return ResponseEntity.ok(response);
    }
    
    /**
     * 审查单个文件
     *
     * <p>需`REVIEW`权限。</p>
     */
    @PostMapping("/file")
    @SaCheckPermission("REVIEW")
    public ResponseEntity<ReviewResponseDTO> reviewFile(@Valid @RequestBody ReviewRequestDTO request) {
        request.setReviewType("FILE");
        ReviewResponseDTO response = reviewService.createReviewTask(request);
        return ResponseEntity.ok(response);
    }
    
    /**
     * 审查指定目录
     *
     * <p>需`REVIEW`权限。</p>
     */
    @PostMapping("/directory")
    @SaCheckPermission("REVIEW")
    public ResponseEntity<ReviewResponseDTO> reviewDirectory(@Valid @RequestBody ReviewRequestDTO request) {
        request.setReviewType("DIRECTORY");
        ReviewResponseDTO response = reviewService.createReviewTask(request);
        return ResponseEntity.ok(response);
    }
    
    /**
     * 审查整个项目
     *
     * <p>需`REVIEW`权限。</p>
     */
    @PostMapping("/project")
    @SaCheckPermission("REVIEW")
    public ResponseEntity<ReviewResponseDTO> reviewProject(@Valid @RequestBody ReviewRequestDTO request) {
        request.setReviewType("PROJECT");
        ReviewResponseDTO response = reviewService.createReviewTask(request);
        return ResponseEntity.ok(response);
    }
    
    /**
     * 审查Git项目
     *
     * <p>需`REVIEW`权限。</p>
     */
    @PostMapping("/git")
    @SaCheckPermission("REVIEW")
    public ResponseEntity<ReviewResponseDTO> reviewGitProject(@Valid @RequestBody ReviewRequestDTO request) {
        request.setReviewType("GIT");
        ReviewResponseDTO response = reviewService.createReviewTask(request);
        return ResponseEntity.ok(response);
    }
    
    /**
     * 下载Git项目并返回文件列表（用于前端显示文件树）
     *
     * <p>需`REVIEW`权限。</p>
     */
    @PostMapping("/git/clone")
    @SaCheckPermission("REVIEW")
    public ResponseEntity<GitCloneResponseDTO> cloneGitRepository(@RequestBody ReviewRequestDTO request) {
        try {
            String gitUrl = request.getGitUrl();
            String username = request.getGitUsername();
            String password = request.getGitPassword();
            
            if (gitUrl == null || gitUrl.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(GitCloneResponseDTO.builder()
                        .success(false)
                        .error("Git仓库地址不能为空")
                        .build());
            }
            
            // 克隆仓库
            String localPath = gitService.cloneRepository(gitUrl, username, password);
            
            // 获取配置的范围
            String includePaths = configService.getSettings().getIncludePaths();
            String excludePaths = configService.getSettings().getExcludePaths();
            
            // 获取文件列表
            List<String> fileList = gitService.getFileList(localPath, includePaths, excludePaths);
            
            return ResponseEntity.ok(GitCloneResponseDTO.builder()
                    .localPath(localPath)
                    .fileList(fileList)
                    .success(true)
                    .build());
        } catch (Exception e) {
            log.error("克隆Git仓库失败", e);
            return ResponseEntity.status(500).body(GitCloneResponseDTO.builder()
                    .success(false)
                    .error("克隆Git仓库失败: " + e.getMessage())
                    .build());
        }
    }
    
    /**
     * 读取Git项目中的文件内容
     *
     * <p>需`QUERY`权限。</p>
     */
    @GetMapping("/git/file")
    @SaCheckPermission("QUERY")
    public ResponseEntity<GitFileResponseDTO> readGitFile(@RequestParam("path") String filePath) {
        try {
            // filePath是完整路径（basePath + relativePath）
            String content = gitService.readFile(filePath);
            
            return ResponseEntity.ok(GitFileResponseDTO.builder()
                    .content(content)
                    .success(true)
                    .build());
        } catch (Exception e) {
            log.error("读取Git文件失败", e);
            return ResponseEntity.status(500).body(GitFileResponseDTO.builder()
                    .success(false)
                    .error("读取文件失败: " + e.getMessage())
                    .build());
        }
    }

    /**
     * 获取服务器端配置的项目根目录文件列表
     *
     * <p>需`REVIEW`权限。</p>
     */
    @GetMapping("/server/list")
    @SaCheckPermission("REVIEW")
    public ResponseEntity<GitCloneResponseDTO> getServerFileList() {
        try {
            String projectRoot = configService.getSettings().getProjectRoot();
            
            if (projectRoot == null || projectRoot.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(GitCloneResponseDTO.builder()
                        .success(false)
                        .error("未配置项目根目录")
                        .build());
            }
            
            // 获取配置的范围
            String includePaths = configService.getSettings().getIncludePaths();
            String excludePaths = configService.getSettings().getExcludePaths();
            
            // 获取文件列表
            List<String> fileList = gitService.getFileList(projectRoot, includePaths, excludePaths);
            
            return ResponseEntity.ok(GitCloneResponseDTO.builder()
                    .localPath(projectRoot)
                    .fileList(fileList)
                    .success(true)
                    .build());
        } catch (Exception e) {
            log.error("获取服务器文件列表失败", e);
            return ResponseEntity.status(500).body(GitCloneResponseDTO.builder()
                    .success(false)
                    .error("获取文件列表失败: " + e.getMessage())
                    .build());
        }
    }

    /**
     * 读取服务器端文件内容
     *
     * <p>需`REVIEW`权限。</p>
     */
    @GetMapping("/server/file")
    @SaCheckPermission("REVIEW")
    public ResponseEntity<GitFileResponseDTO> readServerFile(@RequestParam("path") String relativePath) {
        try {
            String projectRoot = configService.getSettings().getProjectRoot();
            if (projectRoot == null || projectRoot.trim().isEmpty()) {
                throw new IllegalArgumentException("未配置项目根目录");
            }
            
            // 构建完整路径
            // 注意：这里简单拼接，实际生产环境应该防止目录遍历攻击，但作为内部工具且projectRoot受控，暂且从简
            java.nio.file.Path rootPath = java.nio.file.Paths.get(projectRoot);
            java.nio.file.Path filePath = rootPath.resolve(relativePath).normalize();
            
            // 确保文件在projectRoot下
            if (!filePath.startsWith(rootPath)) {
                 throw new IllegalArgumentException("非法的文件路径");
            }
            
            String content = gitService.readFile(filePath.toString());
            
            return ResponseEntity.ok(GitFileResponseDTO.builder()
                    .content(content)
                    .success(true)
                    .build());
        } catch (Exception e) {
            log.error("读取服务器文件失败", e);
            return ResponseEntity.status(500).body(GitFileResponseDTO.builder()
                    .success(false)
                    .error("读取文件失败: " + e.getMessage())
                    .build());
        }
    }
    
    /**
     * 获取审查任务详情
     *
     * <p>需`QUERY`权限。</p>
     */
    @GetMapping("/task/{taskId}")
    @SaCheckPermission("QUERY")
    public ResponseEntity<ReviewResponseDTO> getTask(@PathVariable("taskId") Long taskId) {
        ReviewResponseDTO response = reviewService.getReviewTask(taskId);
        return ResponseEntity.ok(response);
    }
    
    /**
     * 获取审查发现的问题列表
     *
     * <p>需`QUERY`权限。</p>
     */
    @GetMapping("/task/{taskId}/findings")
    @SaCheckPermission("QUERY")
    public ResponseEntity<List<FindingDTO>> getFindings(@PathVariable("taskId") Long taskId) {
        List<Finding> findings = findingRepository.findByTaskId(taskId);
        
        // 获取最大展示数量配置
        Integer maxIssues = configService.getSettings().getMaxIssues();
        if (maxIssues != null && maxIssues > 0 && findings.size() > maxIssues) {
            findings = findings.subList(0, maxIssues);
        }
        
        List<FindingDTO> dtos = findings.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }
    
    /**
     * 查询审查历史
     *
     * <p>需`QUERY`权限。</p>
     */
    @GetMapping("/history")
    @SaCheckPermission("QUERY")
    public ResponseEntity<Page<ReviewResponseDTO>> getHistory(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "reviewType", required = false) String reviewType,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "startTime", required = false) LocalDateTime startTime,
            @RequestParam(value = "endTime", required = false) LocalDateTime endTime,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "sortBy", defaultValue = "createdAt") String sortBy,
            @RequestParam(value = "sortDir", defaultValue = "DESC") String sortDir) {
        
        Sort sort = sortDir.equalsIgnoreCase("ASC") ? 
                Sort.by(sortBy).ascending() : 
                Sort.by(sortBy).descending();
        Pageable pageable = PageRequest.of(page, size, sort);
        
        Integer reviewTypeCode = reviewType != null && !reviewType.isEmpty() ? ReviewTypeEnum.fromName(reviewType).getValue() : null;
        Integer statusCode = status != null && !status.isEmpty() ? TaskStatusEnum.fromName(status).getValue() : null;
        Page<ReviewTask> tasks = taskRepository.findByConditions(
                name, reviewTypeCode, statusCode, startTime, endTime, pageable);
        
        Page<ReviewResponseDTO> response = tasks.map(task -> {
            List<Finding> findings = findingRepository.findByTaskId(task.getId());
            TaskStatusEnum taskStatus = TaskStatusEnum.fromValue(task.getStatus());
            return ReviewResponseDTO.builder()
                .taskId(task.getId())
                .taskName(ReviewTypeEnum.fromValue(task.getReviewType()) == ReviewTypeEnum.GIT && task.getScope() != null ? task.getScope() : task.getName())
                .status(taskStatus.name())
                .statusLabel(taskStatus.getDesc())
                .errorMessage(task.getErrorMessage())
                .canCancel(taskStatus == TaskStatusEnum.PENDING || taskStatus == TaskStatusEnum.RUNNING)
                .canRetry(taskStatus == TaskStatusEnum.FAILED || taskStatus == TaskStatusEnum.CANCELLED)
                .reviewType(ReviewTypeEnum.fromValue(task.getReviewType()).name())
                .scope(mapScopeLabelByType(task.getReviewType()))
                .createdAt(task.getCreatedAt())
                .totalFindings(findings != null ? findings.size() : 0)
                .criticalCount(countBySeverity(findings, SeverityEnum.CRITICAL.getValue()))
                .highCount(countBySeverity(findings, SeverityEnum.HIGH.getValue()))
                .mediumCount(countBySeverity(findings, SeverityEnum.MEDIUM.getValue()))
                .lowCount(countBySeverity(findings, SeverityEnum.LOW.getValue()))
                .build();
        });
        
        return ResponseEntity.ok(response);
    }

    @PostMapping("/task/{taskId}/cancel")
    @SaCheckPermission("REVIEW")
    public ResponseEntity<ReviewResponseDTO> cancelTask(@PathVariable("taskId") Long taskId) {
        return ResponseEntity.ok(reviewService.cancelReviewTask(taskId));
    }

    @PostMapping("/task/{taskId}/retry")
    @SaCheckPermission("REVIEW")
    public ResponseEntity<ReviewResponseDTO> retryTask(@PathVariable("taskId") Long taskId) {
        return ResponseEntity.ok(reviewService.retryReviewTask(taskId));
    }
    
    @DeleteMapping("/task/{taskId}")
    @SaCheckPermission("REVIEW")
    public ResponseEntity<Void> deleteTask(@PathVariable("taskId") Long taskId) {
        Optional<ReviewTask> opt = taskRepository.findById(taskId);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        long evidenceCount = evidenceRepository.countByTaskId(taskId);
        long auditEventCount = auditEventRepository.countByTaskId(taskId);
        if (evidenceCount > 0 || auditEventCount > 0) {
            log.warn("拒绝物理删除已留痕审查任务: taskId={}, evidenceCount={}, auditEventCount={}",
                    taskId, evidenceCount, auditEventCount);
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        taskRepository.delete(opt.get());
        return ResponseEntity.ok().build();
    }
    
    private FindingDTO convertToDTO(Finding finding) {
        return FindingDTO.builder()
                .id(finding.getId())
                .severity(com.codeguardian.enums.SeverityEnum.fromValue(finding.getSeverity()).name())
                .title(finding.getTitle())
                .location(finding.getLocation())
                .startLine(finding.getStartLine())
                .endLine(finding.getEndLine())
                .description(finding.getDescription())
                .suggestion(finding.getSuggestion())
                .diff(finding.getDiff())
                .category(finding.getCategory())
                .source(finding.getSource())
                .grounded(finding.getGrounded())
                .evidenceCount(finding.getEvidenceCount())
                .evidenceHash(finding.getEvidenceHash())
                .groundingSummary(finding.getGroundingSummary())
                .build();
    }
    
    private int countBySeverity(List<Finding> findings, Integer severity) {
        if (findings == null) return 0;
        return (int) findings.stream()
                .filter(f -> severity.equals(f.getSeverity()))
                .count();
    }
    
    private String mapScopeLabelByType(Integer reviewType) {
        ReviewTypeEnum e = ReviewTypeEnum.fromValue(reviewType);
        if (e == ReviewTypeEnum.PROJECT) return "整个项目";
        if (e == ReviewTypeEnum.DIRECTORY) return "指定目录";
        if (e == ReviewTypeEnum.FILE) return "指定文件";
        if (e == ReviewTypeEnum.GIT) return "git项目";
        return "代码片段";
    }
}
