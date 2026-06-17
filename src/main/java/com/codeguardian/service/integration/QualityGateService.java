package com.codeguardian.service.integration;

import com.codeguardian.dto.ReviewGroundingPolicyDTO;
import com.codeguardian.entity.Finding;
import com.codeguardian.enums.SeverityEnum;
import com.codeguardian.repository.FindingRepository;
import com.codeguardian.service.integration.QualityGateProofAssuranceEvaluator.ProofAssurance;
import com.codeguardian.service.provenance.ReviewGroundingPolicyService;
import com.codeguardian.service.provenance.ReviewProofBundleService;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 质量门禁服务
 * 用于判断审查结果是否满足通过条件
 */
@Service
@Slf4j
public class QualityGateService {

    private static final SeverityEnum DEFAULT_GROUNDING_MIN_SEVERITY = SeverityEnum.HIGH;

    private final FindingRepository findingRepository;
    private final ReviewGroundingPolicyService groundingPolicyService;
    private final ReviewProofBundleService proofBundleService;

    public QualityGateService(FindingRepository findingRepository) {
        this(findingRepository, null, null);
    }

    public QualityGateService(FindingRepository findingRepository,
                              ReviewGroundingPolicyService groundingPolicyService) {
        this(findingRepository, groundingPolicyService, null);
    }

    @Autowired
    public QualityGateService(FindingRepository findingRepository,
                              ReviewGroundingPolicyService groundingPolicyService,
                              ReviewProofBundleService proofBundleService) {
        this.findingRepository = findingRepository;
        this.groundingPolicyService = groundingPolicyService;
        this.proofBundleService = proofBundleService;
    }

    /**
     * 检查是否通过质量门禁
     *
     * @param task 审查任务
     * @param blockOn 阻断级别 (CRITICAL, HIGH, MEDIUM, LOW)
     * @return true if passed, false if blocked
     */
    public boolean checkQualityGate(Long taskId, String blockOn) {
        return evaluateQualityGate(taskId, blockOn).isPassed();
    }

    public QualityGateResult evaluateQualityGate(Long taskId, String blockOn) {
        if (taskId == null) {
            return QualityGateResult.builder()
                    .passed(true)
                    .reason("no_task")
                    .build();
        }

        List<Finding> findings = findingRepository.findByTaskId(taskId);
        ReviewGroundingPolicyDTO groundingPolicy = groundingPolicyService != null
                ? groundingPolicyService.evaluateTaskPolicy(taskId, findings)
                : null;
        ProofAssurance proofAssurance = evaluateProofAssurance(taskId);
        return evaluateQualityGate(findings, blockOn, taskId, true, groundingPolicy, proofAssurance);
    }

    boolean checkQualityGate(List<Finding> findings, String blockOn) {
        return evaluateQualityGate(findings, blockOn, null, true, null, null).isPassed();
    }

    QualityGateResult evaluateQualityGate(List<Finding> findings, String blockOn) {
        return evaluateQualityGate(findings, blockOn, null, true, null, null);
    }

    private QualityGateResult evaluateQualityGate(List<Finding> findings,
                                                  String blockOn,
                                                  Long taskId,
                                                  boolean requireGrounding,
                                                  ReviewGroundingPolicyDTO groundingPolicy,
                                                  ProofAssurance proofAssurance) {
        List<Finding> safeFindings = findings != null ? findings : Collections.emptyList();
        Map<Integer, Long> counts = safeFindings.stream()
                .filter(f -> f.getSeverity() != null)
                .collect(Collectors.groupingBy(Finding::getSeverity, Collectors.counting()));

        long critical = counts.getOrDefault(SeverityEnum.CRITICAL.getValue(), 0L);
        long high = counts.getOrDefault(SeverityEnum.HIGH.getValue(), 0L);
        long medium = counts.getOrDefault(SeverityEnum.MEDIUM.getValue(), 0L);
        long low = counts.getOrDefault(SeverityEnum.LOW.getValue(), 0L);

        log.info("质量门禁检查: taskId={}, blockOn={}, counts={C:{}, H:{}, M:{}, L:{}}",
                taskId, blockOn, critical, high, medium, low);

        boolean severityBlocked = shouldBlockBySeverity(critical, high, medium, low, blockOn);
        long ungroundedCritical = countUngroundedAtSeverity(safeFindings, SeverityEnum.CRITICAL);
        long ungroundedHigh = countUngroundedAtSeverity(safeFindings, SeverityEnum.HIGH);
        long ungroundedTotal = ungroundedCritical + ungroundedHigh;
        boolean policyBlocked = requireGrounding
                && groundingPolicy != null
                && !Boolean.TRUE.equals(groundingPolicy.getValid());
        boolean provenanceBlocked = requireGrounding && (ungroundedTotal > 0 || policyBlocked);
        boolean integrityBlocked = proofAssurance != null && !proofAssurance.isValid();
        boolean passed = !severityBlocked && !provenanceBlocked && !integrityBlocked;

        String reason = "ok";
        if (severityBlocked && provenanceBlocked && integrityBlocked) {
            reason = "severity_provenance_and_integrity_blocked";
        } else if (severityBlocked && provenanceBlocked) {
            reason = "severity_and_provenance_blocked";
        } else if (severityBlocked && integrityBlocked) {
            reason = "severity_and_integrity_blocked";
        } else if (provenanceBlocked && integrityBlocked) {
            reason = "provenance_and_integrity_blocked";
        } else if (severityBlocked) {
            reason = "severity_blocked";
        } else if (provenanceBlocked) {
            reason = "provenance_blocked";
        } else if (integrityBlocked) {
            reason = "integrity_blocked";
        }

        if (provenanceBlocked) {
            log.warn("高严谨门禁拦截未溯源高风险问题: taskId={}, ungroundedCritical={}, ungroundedHigh={}, policyViolations={}",
                    taskId, ungroundedCritical, ungroundedHigh,
                    groundingPolicy != null ? groundingPolicy.getViolationCount() : 0);
        }

        return QualityGateResult.builder()
                .passed(passed)
                .reason(reason)
                .severityBlocked(severityBlocked)
                .provenanceBlocked(provenanceBlocked)
                .integrityBlocked(integrityBlocked)
                .blockOn(blockOn)
                .groundingMinSeverity(DEFAULT_GROUNDING_MIN_SEVERITY.name())
                .criticalCount(critical)
                .highCount(high)
                .mediumCount(medium)
                .lowCount(low)
                .ungroundedCriticalCount(ungroundedCritical)
                .ungroundedHighCount(ungroundedHigh)
                .ungroundedRiskCount(ungroundedTotal)
                .groundingPolicyValid(groundingPolicy != null ? groundingPolicy.getValid() : null)
                .groundingPolicyReason(groundingPolicy != null ? groundingPolicy.getReason() : null)
                .groundingViolationCount(groundingPolicy != null ? safeInt(groundingPolicy.getViolationCount()) : 0)
                .missingSourceEvidenceCount(groundingPolicy != null ? safeInt(groundingPolicy.getMissingSourceEvidenceCount()) : 0)
                .evidenceCountMismatchCount(groundingPolicy != null ? safeInt(groundingPolicy.getEvidenceCountMismatchCount()) : 0)
                .evidenceHashMismatchCount(groundingPolicy != null ? safeInt(groundingPolicy.getEvidenceHashMismatchCount()) : 0)
                .invalidEvidenceContentHashCount(groundingPolicy != null ? safeInt(groundingPolicy.getInvalidEvidenceContentHashCount()) : 0)
                .invalidSourceAnchorCount(groundingPolicy != null ? safeInt(groundingPolicy.getInvalidSourceAnchorCount()) : 0)
                .proofBundleValid(proofAssurance != null ? proofAssurance.getProofBundleValid() : null)
                .proofBundleReason(proofAssurance != null ? proofAssurance.getProofBundleReason() : null)
                .auditChainValid(proofAssurance != null ? proofAssurance.getAuditChainValid() : null)
                .auditSignatureValid(proofAssurance != null ? proofAssurance.getAuditSignatureValid() : null)
                .auditCoverageValid(proofAssurance != null ? proofAssurance.getAuditCoverageValid() : null)
                .auditOrderValid(proofAssurance != null ? proofAssurance.getAuditOrderValid() : null)
                .evidenceHashesValid(proofAssurance != null ? proofAssurance.getEvidenceHashValid() : null)
                .reviewStateHashValid(proofAssurance != null ? proofAssurance.getReviewStateHashValid() : null)
                .runtimeManifestHashValid(proofAssurance != null ? proofAssurance.getRuntimeManifestHashValid() : null)
                .bundleHashValid(proofAssurance != null ? proofAssurance.getBundleHashValid() : null)
                .bundleSignatureValid(proofAssurance != null ? proofAssurance.getBundleSignatureValid() : null)
                .currentStateMatch(proofAssurance != null ? proofAssurance.getCurrentStateMatch() : null)
                .currentReviewStateMatch(proofAssurance != null ? proofAssurance.getCurrentReviewStateMatch() : null)
                .currentRuntimeManifestMatch(proofAssurance != null ? proofAssurance.getCurrentRuntimeManifestMatch() : null)
                .currentBundleMatch(proofAssurance != null ? proofAssurance.getCurrentBundleMatch() : null)
                .runtimeGuardValid(proofAssurance != null ? proofAssurance.getRuntimeGuardValid() : null)
                .dbGuardQuerySupported(proofAssurance != null ? proofAssurance.getDbGuardQuerySupported() : null)
                .dbAppendOnlyGuardsInstalled(proofAssurance != null ? proofAssurance.getDbAppendOnlyGuardsInstalled() : null)
                .dbGuardUpdatesBlocked(proofAssurance != null ? proofAssurance.getDbGuardUpdatesBlocked() : null)
                .dbGuardDeletesBlocked(proofAssurance != null ? proofAssurance.getDbGuardDeletesBlocked() : null)
                .dbGuardReason(proofAssurance != null ? proofAssurance.getDbGuardReason() : null)
                .build();
    }

    private ProofAssurance evaluateProofAssurance(Long taskId) {
        return new QualityGateProofAssuranceEvaluator(proofBundleService).evaluate(taskId);
    }

    private int safeInt(Integer value) {
        return value != null ? value : 0;
    }

    private boolean shouldBlockBySeverity(long critical, long high, long medium, long low, String blockOn) {
        if (blockOn == null || blockOn.isBlank()) {
            return false;
        }
        switch (blockOn.toUpperCase()) {
            case "LOW":
                if (low > 0) return true;
                // fallthrough
            case "MEDIUM":
                if (medium > 0) return true;
                // fallthrough
            case "HIGH":
                if (high > 0) return true;
                // fallthrough
            case "CRITICAL":
                return critical > 0;
            default:
                log.warn("未知的阻断级别: {}, 忽略阻断策略", blockOn);
                return false;
        }
    }

    private long countUngroundedAtSeverity(List<Finding> findings, SeverityEnum severity) {
        return findings.stream()
                .filter(f -> severity.getValue().equals(f.getSeverity()))
                .filter(this::isUngrounded)
                .count();
    }

    private boolean isUngrounded(Finding finding) {
        boolean grounded = Boolean.TRUE.equals(finding.getGrounded());
        int evidenceCount = finding.getEvidenceCount() != null ? finding.getEvidenceCount() : 0;
        return !grounded || evidenceCount <= 0;
    }

    @Data
    @Builder
    public static class QualityGateResult {
        private boolean passed;
        private String reason;
        private boolean severityBlocked;
        private boolean provenanceBlocked;
        private boolean integrityBlocked;
        private String blockOn;
        private String groundingMinSeverity;
        private long criticalCount;
        private long highCount;
        private long mediumCount;
        private long lowCount;
        private long ungroundedCriticalCount;
        private long ungroundedHighCount;
        private long ungroundedRiskCount;
        private Boolean groundingPolicyValid;
        private String groundingPolicyReason;
        private int groundingViolationCount;
        private int missingSourceEvidenceCount;
        private int evidenceCountMismatchCount;
        private int evidenceHashMismatchCount;
        private int invalidEvidenceContentHashCount;
        private int invalidSourceAnchorCount;
        private Boolean proofBundleValid;
        private String proofBundleReason;
        private Boolean auditChainValid;
        private Boolean auditSignatureValid;
        private Boolean auditCoverageValid;
        private Boolean auditOrderValid;
        private Boolean evidenceHashesValid;
        private Boolean reviewStateHashValid;
        private Boolean runtimeManifestHashValid;
        private Boolean bundleHashValid;
        private Boolean bundleSignatureValid;
        private Boolean currentStateMatch;
        private Boolean currentReviewStateMatch;
        private Boolean currentRuntimeManifestMatch;
        private Boolean currentBundleMatch;
        private Boolean runtimeGuardValid;
        private Boolean dbGuardQuerySupported;
        private Boolean dbAppendOnlyGuardsInstalled;
        private Boolean dbGuardUpdatesBlocked;
        private Boolean dbGuardDeletesBlocked;
        private String dbGuardReason;
    }

}
