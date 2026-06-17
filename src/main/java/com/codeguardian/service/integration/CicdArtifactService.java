package com.codeguardian.service.integration;

import com.codeguardian.dto.ReviewResponseDTO;
import com.codeguardian.dto.integration.CicdStatusResponse;
import com.codeguardian.entity.Finding;
import com.codeguardian.entity.ReviewTask;
import com.codeguardian.enums.SeverityEnum;
import com.codeguardian.enums.TaskStatusEnum;
import com.codeguardian.repository.FindingRepository;
import com.codeguardian.repository.ReviewTaskRepository;
import com.codeguardian.service.ReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CicdArtifactService {

    private final ReviewTaskRepository taskRepository;
    private final FindingRepository findingRepository;
    private final ReviewService reviewService;
    private final QualityGateService qualityGateService;
    private final CicdTaskConfigRegistry configRegistry;
    private final PrDiffService prDiffService;
    private final QualityJudgeService qualityJudgeService;

    public Map<String, Object> buildResult(Long taskId, String blockOn) {
        ReviewTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("task_not_found"));
        String effectiveBlockOn = effectiveBlockOn(taskId, blockOn);
        QualityGateService.QualityGateResult gate = qualityGateService.evaluateQualityGate(taskId, effectiveBlockOn);
        ReviewResponseDTO summary = reviewService.getReviewTask(taskId);
        List<Finding> findings = findingRepository.findByTaskId(taskId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", "codeguardian-cicd-result-v1");
        result.put("taskId", task.getId());
        result.put("taskName", task.getName());
        result.put("status", TaskStatusEnum.fromValue(task.getStatus()).name());
        result.put("passed", gate.isPassed());
        result.put("reason", gate.getReason());
        result.put("blockOn", effectiveBlockOn);
        result.put("reportUrl", "/report/" + task.getId());
        result.put("htmlReportUrl", "/api/report/" + task.getId() + "/html");
        result.put("pdfReportUrl", "/api/report/" + task.getId() + "/pdf");
        result.put("proofBundleUrl", "/api/review/task/" + task.getId() + "/proof-bundle/archive");
        result.put("traceGraphUrl", "/api/review/task/" + task.getId() + "/trace-graph");
        result.put("createdAt", task.getCreatedAt() != null ? task.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null);
        result.put("completedAt", task.getCompletedAt() != null ? task.getCompletedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null);
        result.put("summary", summary(summary));
        result.put("qualityGate", qualityGate(gate));
        result.put("qualityJudge", qualityJudge(qualityJudgeService.evaluate(findings)));
        result.put("findings", findings.stream().map(this::finding).toList());
        configRegistry.get(taskId).ifPresent(config -> {
            result.put("cicdConfig", Map.of(
                    "blockOn", nullToEmpty(config.getBlockOn()),
                    "diffOnly", Boolean.TRUE.equals(config.getDiffOnly()),
                    "inlineComments", Boolean.TRUE.equals(config.getInlineComments()),
                    "changedFileCount", config.getChangedFiles() != null ? config.getChangedFiles().size() : 0,
                    "configSource", nullToEmpty(config.getConfigSource())
            ));
            result.put("inlineComments", prDiffService.buildInlineComments(findings, config.getUnifiedDiff())
                    .stream()
                    .map(this::inlineComment)
                    .toList());
        });
        return result;
    }

    public Map<String, Object> buildSarif(Long taskId) {
        ReviewTask task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("task_not_found"));
        List<Finding> findings = findingRepository.findByTaskId(taskId);
        Map<String, Object> driver = new LinkedHashMap<>();
        driver.put("name", "CodeGuardian AI");
        driver.put("informationUri", "https://localhost/codeguardian");
        driver.put("rules", findings.stream().map(this::sarifRule).distinct().toList());

        Map<String, Object> run = new LinkedHashMap<>();
        run.put("tool", Map.of("driver", driver));
        run.put("automationDetails", Map.of("id", "codeguardian-task-" + taskId));
        run.put("results", findings.stream().map(this::sarifResult).toList());

        Map<String, Object> sarif = new LinkedHashMap<>();
        sarif.put("$schema", "https://json.schemastore.org/sarif-2.1.0.json");
        sarif.put("version", "2.1.0");
        sarif.put("runs", List.of(run));
        return sarif;
    }

    private String effectiveBlockOn(Long taskId, String blockOn) {
        if (blockOn != null && !blockOn.isBlank()) {
            return blockOn;
        }
        return configRegistry.get(taskId)
                .map(config -> config.getBlockOn())
                .filter(value -> value != null && !value.isBlank())
                .orElse("CRITICAL");
    }

    private Map<String, Object> summary(ReviewResponseDTO summary) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("critical", safeInt(summary.getCriticalCount()));
        result.put("high", safeInt(summary.getHighCount()));
        result.put("medium", safeInt(summary.getMediumCount()));
        result.put("low", safeInt(summary.getLowCount()));
        result.put("total", safeInt(summary.getTotalFindings()));
        return result;
    }

    private Map<String, Object> finding(Finding finding) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", finding.getId());
        result.put("severity", severity(finding.getSeverity()));
        result.put("title", finding.getTitle());
        result.put("location", finding.getLocation());
        result.put("startLine", finding.getStartLine());
        result.put("endLine", finding.getEndLine());
        result.put("category", finding.getCategory());
        result.put("source", finding.getSource());
        result.put("grounded", Boolean.TRUE.equals(finding.getGrounded()));
        result.put("evidenceCount", finding.getEvidenceCount());
        result.put("evidenceHash", finding.getEvidenceHash());
        result.put("description", finding.getDescription());
        result.put("suggestion", finding.getSuggestion());
        return result;
    }

    private Map<String, Object> qualityGate(QualityGateService.QualityGateResult gate) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("passed", gate.isPassed());
        result.put("reason", gate.getReason());
        result.put("severityBlocked", gate.isSeverityBlocked());
        result.put("provenanceBlocked", gate.isProvenanceBlocked());
        result.put("integrityBlocked", gate.isIntegrityBlocked());
        result.put("ungroundedCritical", gate.getUngroundedCriticalCount());
        result.put("ungroundedHigh", gate.getUngroundedHighCount());
        result.put("groundingPolicyValid", gate.getGroundingPolicyValid());
        result.put("proofBundleValid", gate.getProofBundleValid());
        result.put("auditChainValid", gate.getAuditChainValid());
        result.put("auditCoverageValid", gate.getAuditCoverageValid());
        result.put("auditOrderValid", gate.getAuditOrderValid());
        result.put("runtimeGuardValid", gate.getRuntimeGuardValid());
        result.put("dbAppendOnlyGuardsInstalled", gate.getDbAppendOnlyGuardsInstalled());
        return result;
    }

    private Map<String, Object> inlineComment(PrInlineComment comment) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("findingId", comment.getFindingId());
        result.put("path", nullToEmpty(comment.getPath()));
        result.put("line", comment.getLine());
        result.put("side", nullToEmpty(comment.getSide()));
        result.put("severity", nullToEmpty(comment.getSeverity()));
        result.put("title", nullToEmpty(comment.getTitle()));
        result.put("body", nullToEmpty(comment.getBody()));
        result.put("publishable", Boolean.TRUE.equals(comment.getPublishable()));
        result.put("reason", nullToEmpty(comment.getReason()));
        return result;
    }

    private Map<String, Object> qualityJudge(QualityJudgeResult judge) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("score", judge.getScore());
        result.put("grade", judge.getGrade());
        result.put("reason", judge.getReason());
        result.put("duplicateRate", judge.getDuplicateRate());
        result.put("evidenceMissingRate", judge.getEvidenceMissingRate());
        result.put("highRiskGroundingRate", judge.getHighRiskGroundingRate());
        result.put("p1CoverageRate", judge.getP1CoverageRate());
        result.put("ragHitRate", judge.getRagHitRate());
        result.put("duplicateCount", judge.getDuplicateCount());
        result.put("evidenceMissingCount", judge.getEvidenceMissingCount());
        result.put("highRiskCount", judge.getHighRiskCount());
        result.put("groundedHighRiskCount", judge.getGroundedHighRiskCount());
        return result;
    }

    private Map<String, Object> sarifRule(Finding finding) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("id", ruleId(finding));
        rule.put("name", safeText(finding.getTitle(), ruleId(finding)));
        rule.put("shortDescription", Map.of("text", safeText(finding.getTitle(), ruleId(finding))));
        return rule;
    }

    private Map<String, Object> sarifResult(Finding finding) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ruleId", ruleId(finding));
        result.put("level", sarifLevel(finding.getSeverity()));
        result.put("message", Map.of("text", safeText(finding.getDescription(), safeText(finding.getTitle(), ruleId(finding)))));
        result.put("locations", List.of(Map.of("physicalLocation", physicalLocation(finding))));
        result.put("properties", Map.of(
                "severity", severity(finding.getSeverity()),
                "grounded", Boolean.TRUE.equals(finding.getGrounded()),
                "evidenceCount", finding.getEvidenceCount() != null ? finding.getEvidenceCount() : 0,
                "evidenceHash", nullToEmpty(finding.getEvidenceHash())
        ));
        return result;
    }

    private Map<String, Object> physicalLocation(Finding finding) {
        Map<String, Object> region = new LinkedHashMap<>();
        if (finding.getStartLine() != null) {
            region.put("startLine", Math.max(1, finding.getStartLine()));
        }
        if (finding.getEndLine() != null) {
            region.put("endLine", Math.max(1, finding.getEndLine()));
        }
        Map<String, Object> location = new LinkedHashMap<>();
        location.put("artifactLocation", Map.of("uri", artifactUri(finding.getLocation())));
        if (!region.isEmpty()) {
            location.put("region", region);
        }
        return location;
    }

    private String artifactUri(String location) {
        if (location == null || location.isBlank()) {
            return "unknown";
        }
        String normalized = location.replace("\\", "/");
        int colon = normalized.indexOf(':');
        return colon > 1 ? normalized.substring(0, colon).trim() : normalized.trim();
    }

    private String ruleId(Finding finding) {
        if (finding.getCategory() != null && !finding.getCategory().isBlank()) {
            return finding.getCategory();
        }
        if (finding.getTitle() != null && !finding.getTitle().isBlank()) {
            return finding.getTitle().toLowerCase().replaceAll("[^a-z0-9._-]+", "-");
        }
        return "codeguardian.finding";
    }

    private String sarifLevel(Integer severity) {
        SeverityEnum value = SeverityEnum.fromValue(severity);
        return switch (value) {
            case CRITICAL, HIGH -> "error";
            case MEDIUM -> "warning";
            case LOW -> "note";
        };
    }

    private String severity(Integer severity) {
        return SeverityEnum.fromValue(severity).name();
    }

    private String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    private String safeText(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private int safeInt(Integer value) {
        return value != null ? value : 0;
    }
}
