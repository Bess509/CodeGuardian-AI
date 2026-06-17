package com.codeguardian.service;

import static com.codeguardian.service.ReportTextFormatter.*;

import com.codeguardian.dto.ReviewAssuranceSummaryDTO;
import com.codeguardian.dto.ReviewGroundingPolicyDTO;
import com.codeguardian.dto.ReviewProofBundleDTO;
import com.codeguardian.dto.ReviewProofBundleVerificationDTO;
import com.codeguardian.dto.ReviewRuntimeManifestDTO;
import com.codeguardian.entity.ReviewEvidence;
import com.codeguardian.entity.ReviewTask;
import com.codeguardian.repository.ReviewEvidenceRepository;
import com.codeguardian.service.provenance.ReviewAssuranceService;
import com.codeguardian.service.provenance.ReviewAuditService;
import com.codeguardian.service.provenance.ReviewProofBundleService;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class ReportTraceabilityRenderer {

    private final ReviewEvidenceRepository evidenceRepository;
    private final ReviewAuditService auditService;
    private final ReviewProofBundleService proofBundleService;
    private final ReviewAssuranceService assuranceService;
    private final ReportProofChainRenderer proofChainRenderer = new ReportProofChainRenderer();

    ReportTraceabilityRenderer(ReviewEvidenceRepository evidenceRepository,
                               ReviewAuditService auditService,
                               ReviewProofBundleService proofBundleService,
                               ReviewAssuranceService assuranceService) {
        this.evidenceRepository = evidenceRepository;
        this.auditService = auditService;
        this.proofBundleService = proofBundleService;
        this.assuranceService = assuranceService;
    }

    String buildTraceabilityPanel(ReviewTask task) {
        ReviewAuditService.IntegrityResult integrity = auditService.verifyTaskChain(task.getId(), task.getStatus());
        ReviewProofBundleDTO proofBundle = proofBundleService.buildBundle(task.getId());
        ReviewProofBundleVerificationDTO proofVerification = proofBundleService.verifyBundleAgainstCurrentState(task.getId(), proofBundle);
        ReviewGroundingPolicyDTO groundingPolicy = proofBundle.getGroundingPolicy();
        ReviewRuntimeManifestDTO runtimeManifest = proofBundle.getRuntimeManifest();
        ReviewAssuranceSummaryDTO assurance = assuranceService != null ? assuranceService.buildSummary(task.getId()) : null;
        String proofBundleHash = proofBundle.getBundleHash();
        List<ReviewEvidence> evidence = evidenceRepository.findByTaskIdOrderByCreatedAtAscIdAsc(task.getId());
        Map<String, Integer> countsByType = new TreeMap<>();
        for (ReviewEvidence item : evidence) {
            String type = item.getEvidenceType() != null ? item.getEvidenceType() : "UNKNOWN";
            countsByType.put(type, countsByType.getOrDefault(type, 0) + 1);
        }
        long ragCount = evidence.stream()
                .filter(item -> "RAG_SNIPPET".equals(item.getEvidenceType()))
                .count();
        long sourceCount = evidence.stream()
                .filter(item -> "SOURCE_CODE".equals(item.getEvidenceType()))
                .count();
        boolean hasSignedEvents = integrity.getSignedEventCount() > 0;
        String signatureClass = !integrity.isSignatureValid() ? "bad" : (hasSignedEvents ? "ok" : "warn");
        String signatureLabel = !integrity.isSignatureValid() ? "异常" : (hasSignedEvents ? "有效" : "未签名");
        String coverageClass = integrity.isAuditCoverageValid() ? "ok" : "bad";
        String coverageLabel = integrity.isAuditCoverageValid() ? "有效" : "缺失";
        String evidenceHashClass = Boolean.TRUE.equals(proofVerification.getEvidenceHashValid()) ? "ok" : "bad";
        String evidenceHashLabel = Boolean.TRUE.equals(proofVerification.getEvidenceHashValid()) ? "有效" : "异常";
        String reviewStateClass = Boolean.TRUE.equals(proofVerification.getReviewStateHashValid()) ? "ok" : "bad";
        String reviewStateLabel = Boolean.TRUE.equals(proofVerification.getReviewStateHashValid()) ? "有效" : "异常";
        String runtimeManifestClass = Boolean.TRUE.equals(proofVerification.getRuntimeManifestHashValid()) ? "ok" : "bad";
        String runtimeManifestLabel = Boolean.TRUE.equals(proofVerification.getRuntimeManifestHashValid()) ? "有效" : "异常";
        ReviewRuntimeManifestDTO.DatabaseGuardSnapshot databaseGuards =
                runtimeManifest != null ? runtimeManifest.getDatabaseGuards() : null;
        boolean dbGuardsInstalled = databaseGuards != null
                && Boolean.TRUE.equals(databaseGuards.getAppendOnlyGuardsInstalled());
        String dbGuardClass = databaseGuards == null || databaseGuards.getAppendOnlyGuardsInstalled() == null
                ? "warn" : (dbGuardsInstalled ? "ok" : "bad");
        String dbGuardLabel = databaseGuards == null || databaseGuards.getAppendOnlyGuardsInstalled() == null
                ? "未知" : (dbGuardsInstalled ? "已安装" : "缺失");
        String groundingPolicyClass = Boolean.TRUE.equals(proofVerification.getGroundingPolicyValid()) ? "ok" : "bad";
        String groundingPolicyLabel = Boolean.TRUE.equals(proofVerification.getGroundingPolicyValid()) ? "有效" : "阻断";

        StringBuilder html = new StringBuilder();
        html.append("<div class=\"panel trace\">\n");
        html.append("  <div class=\"panel-hd\">审查依据与审计链</div>\n");
        html.append("  <div class=\"panel-bd\">\n");
        html.append(buildAssuranceSummaryBlock(assurance));
        html.append(proofChainRenderer.buildProofChainVisualization(
                integrity,
                proofBundle,
                proofVerification,
                groundingPolicy,
                runtimeManifest,
                databaseGuards,
                evidence.size(),
                sourceCount,
                ragCount
        ));
        html.append("    <div class=\"trace-grid\">\n");
        html.append("      <div class=\"trace-cell\"><div class=\"muted\">审计链</div><div class=\"trace-status ")
                .append(integrity.isValid() ? "ok" : "bad")
                .append("\">")
                .append(integrity.isValid() ? "有效" : "异常")
                .append("</div><div class=\"hash\">")
                .append(escapeHtml(shortHash(integrity.getLastHash())))
                .append("</div></div>\n");
        html.append("      <div class=\"trace-cell\"><div class=\"muted\">审计事件</div><div>")
                .append(integrity.getEventCount())
                .append("</div><div class=\"muted\">")
                .append(escapeHtml(integrity.getReason()))
                .append(integrity.getFailedEventId() != null ? " #" + integrity.getFailedEventId() : "")
                .append("</div></div>\n");
        html.append("      <div class=\"trace-cell\"><div class=\"muted\">审计覆盖</div><div class=\"trace-status ")
                .append(coverageClass)
                .append("\">")
                .append(coverageLabel)
                .append("</div><div class=\"muted\">终态=")
                .append(integrity.isTerminalEventConsistent())
                .append("</div><div class=\"hash\">缺失=")
                .append(escapeHtml(integrity.getMissingEventTypes() != null
                        ? String.join(",", integrity.getMissingEventTypes()) : ""))
                .append("</div></div>\n");
        html.append("      <div class=\"trace-cell\"><div class=\"muted\">审计签名</div><div class=\"trace-status ")
                .append(signatureClass)
                .append("\">")
                .append(signatureLabel)
                .append("</div><div class=\"muted\">已签名=")
                .append(integrity.getSignedEventCount())
                .append("</div><div class=\"hash\">密钥=")
                .append(escapeHtml(integrity.getSignatureKeyId()))
                .append("</div></div>\n");
        html.append("      <div class=\"trace-cell\"><div class=\"muted\">证据记录</div><div>")
                .append(evidence.size())
                .append("</div><div class=\"muted\">源码=")
                .append(sourceCount)
                .append(" · RAG=")
                .append(ragCount)
                .append("</div></div>\n");
        html.append("      <div class=\"trace-cell\"><div class=\"muted\">证据哈希</div><div class=\"trace-status ")
                .append(evidenceHashClass)
                .append("\">")
                .append(evidenceHashLabel)
                .append("</div><div class=\"muted\">无效=")
                .append(proofVerification.getInvalidEvidenceCount() != null ? proofVerification.getInvalidEvidenceCount() : 0)
                .append("</div></div>\n");
        html.append("      <div class=\"trace-cell\"><div class=\"muted\">审查状态</div><div class=\"trace-status ")
                .append(reviewStateClass)
                .append("\">")
                .append(reviewStateLabel)
                .append("</div><div class=\"hash\">")
                .append(escapeHtml(shortHash(proofBundle.getReviewStateHash())))
                .append("</div></div>\n");
        html.append("      <div class=\"trace-cell\"><div class=\"muted\">运行清单</div><div class=\"trace-status ")
                .append(runtimeManifestClass)
                .append("\">")
                .append(runtimeManifestLabel)
                .append("</div><div class=\"hash\">")
                .append(escapeHtml(shortHash(runtimeManifest != null ? runtimeManifest.getManifestHash() : null)))
                .append("</div></div>\n");
        html.append("      <div class=\"trace-cell\"><div class=\"muted\">数据库只追加保护</div><div class=\"trace-status ")
                .append(dbGuardClass)
                .append("\">")
                .append(dbGuardLabel)
                .append("</div><div class=\"muted\">触发器=")
                .append(databaseGuards != null && databaseGuards.getInstalledTriggerCount() != null
                        ? databaseGuards.getInstalledTriggerCount() : "?")
                .append("/")
                .append(databaseGuards != null && databaseGuards.getExpectedTriggerCount() != null
                        ? databaseGuards.getExpectedTriggerCount() : "?")
                .append("</div><div class=\"hash\">")
                .append(escapeHtml(databaseGuards != null ? databaseGuards.getVerificationReason() : "missing"))
                .append("</div></div>\n");
        html.append("      <div class=\"trace-cell\"><div class=\"muted\">溯源策略</div><div class=\"trace-status ")
                .append(groundingPolicyClass)
                .append("\">")
                .append(groundingPolicyLabel)
                .append("</div><div class=\"muted\">违规=")
                .append(proofVerification.getGroundingViolationCount() != null ? proofVerification.getGroundingViolationCount() : 0)
                .append(" · 源码锚点无效=")
                .append(groundingPolicy != null && groundingPolicy.getInvalidSourceAnchorCount() != null
                        ? groundingPolicy.getInvalidSourceAnchorCount() : 0)
                .append("</div><div class=\"hash\">最低=")
                .append(escapeHtml(groundingPolicy != null ? groundingPolicy.getMinSeverity() : "UNKNOWN"))
                .append("</div></div>\n");
        html.append("      <div class=\"trace-cell\"><div class=\"muted\">验证接口</div><div class=\"hash\">/api/review/task/")
                .append(task.getId())
                .append("/assurance-summary</div><div class=\"hash\">/api/review/task/")
                .append(task.getId())
                .append("/integrity</div><div class=\"hash\">/api/review/task/")
                .append(task.getId())
                .append("/runtime-manifest</div><div class=\"hash\">/api/review/task/")
                .append(task.getId())
                .append("/grounding-policy</div><div class=\"hash\">/api/review/task/")
                .append(task.getId())
                .append("/trace-graph</div><div class=\"hash\">/api/review/task/")
                .append(task.getId())
                .append("/evidence</div><div class=\"hash\">/api/review/task/")
                .append(task.getId())
                .append("/proof-bundle</div><div class=\"hash\">/api/review/task/")
                .append(task.getId())
                .append("/proof-bundle/archive</div><div class=\"hash\">POST /api/review/proof-bundle/verify</div><div class=\"hash\">POST /api/review/task/")
                .append(task.getId())
                .append("/proof-bundle/verify-current</div></div>\n");
        html.append("      <div class=\"trace-cell\"><div class=\"muted\">证明包</div><div class=\"trace-status ok\">可导出</div><div class=\"hash\">")
                .append(escapeHtml(shortHash(proofBundleHash)))
                .append("</div><div class=\"hash\">归档: /api/review/task/")
                .append(task.getId())
                .append("/proof-bundle/archive")
                .append("</div></div>\n");
        html.append("    </div>\n");

        if (!countsByType.isEmpty()) {
            html.append("    <div style=\"margin-top:10px\">\n");
            for (Map.Entry<String, Integer> entry : countsByType.entrySet()) {
                html.append("      <span class=\"trace-chip\">")
                        .append(escapeHtml(entry.getKey()))
                        .append("=")
                        .append(entry.getValue())
                        .append("</span>\n");
            }
            html.append("    </div>\n");
        }

        List<ReviewEvidence> ragEvidence = evidence.stream()
                .filter(item -> "RAG_SNIPPET".equals(item.getEvidenceType()))
                .limit(5)
                .toList();
        html.append("    <div class=\"trace-list\">\n");
        if (ragEvidence.isEmpty()) {
            html.append("      <div class=\"trace-row\"><div class=\"muted\">暂无 RAG 证据记录</div><div></div><div></div></div>\n");
        } else {
            for (ReviewEvidence item : ragEvidence) {
                String title = metadataValue(item, "title");
                String mode = displayRetrievalMode(metadataValue(item, "retrievalMode"));
                String sourceDocumentId = metadataValue(item, "sourceDocumentId");
                html.append("      <div class=\"trace-row\">\n");
                html.append("        <div><strong>")
                        .append(escapeHtml(title != null && !title.isBlank() ? title : item.getSourceName()))
                        .append("</strong><br><span class=\"muted\">")
                        .append(escapeHtml(mode))
                        .append(" · ")
                        .append(escapeHtml(sourceDocumentId))
                        .append("</span></div>\n");
                html.append("        <div class=\"hash\">")
                        .append(escapeHtml(item.getSourceRef()))
                        .append("</div>\n");
                html.append("        <div class=\"hash\">")
                        .append(escapeHtml(shortHash(item.getContentHash())))
                        .append("</div>\n");
                html.append("      </div>\n");
            }
        }
        html.append("    </div>\n");
        html.append("  </div>\n");
        html.append("</div>\n");
        return html.toString();
    }

    private String buildAssuranceSummaryBlock(ReviewAssuranceSummaryDTO assurance) {
        StringBuilder html = new StringBuilder();
        String verdict = displayAssuranceVerdict(assurance != null ? assurance.getVerdict() : null);
        boolean valid = assurance != null && Boolean.TRUE.equals(assurance.getValid());
        String statusClass = valid ? "ok" : "bad";
        html.append("    <div class=\"trace-cell\" style=\"margin-bottom:12px\">\n");
        html.append("      <div class=\"muted\">证明结论</div><div class=\"trace-status ")
                .append(statusClass)
                .append("\">")
                .append(escapeHtml(verdict))
                .append("</div>\n");
        if (assurance != null) {
            html.append("      <div class=\"muted\">原因=")
                    .append(escapeHtml(assurance.getReason()))
                    .append("</div>\n");
            html.append("      <div class=\"trace-grid\" style=\"margin-top:10px\">\n");
            html.append("        <div class=\"trace-cell\"><div class=\"muted\">审查状态</div><div class=\"hash\">")
                    .append(escapeHtml(shortHash(assurance.getReviewStateHash())))
                    .append("</div></div>\n");
            html.append("        <div class=\"trace-cell\"><div class=\"muted\">证明包</div><div class=\"hash\">")
                    .append(escapeHtml(shortHash(assurance.getBundleHash())))
                    .append("</div></div>\n");
            html.append("        <div class=\"trace-cell\"><div class=\"muted\">运行环境</div><div class=\"hash\">")
                    .append(escapeHtml(shortHash(assurance.getRuntimeManifestHash())))
                    .append("</div></div>\n");
            html.append("        <div class=\"trace-cell\"><div class=\"muted\">RAG 知识库</div><div class=\"hash\">")
                    .append(escapeHtml(shortHash(assurance.getKnowledgeBaseFingerprint())))
                    .append("</div></div>\n");
            if (assurance.getCounts() != null) {
                html.append("        <div class=\"trace-cell\"><div class=\"muted\">高风险已溯源</div><div>")
                        .append(assurance.getCounts().getGroundedHighRiskFindingCount())
                        .append("/")
                        .append(assurance.getCounts().getHighRiskFindingCount())
                        .append("</div></div>\n");
                html.append("        <div class=\"trace-cell\"><div class=\"muted\">无效证据</div><div>")
                        .append(assurance.getCounts().getInvalidEvidenceCount() != null
                                ? assurance.getCounts().getInvalidEvidenceCount() : 0)
                        .append("</div></div>\n");
            }
            html.append("      </div>\n");

            List<ReviewAssuranceSummaryDTO.AssuranceCheck> failedChecks = assurance.getChecks() != null
                    ? assurance.getChecks().stream()
                    .filter(check -> !Boolean.TRUE.equals(check.getValid()))
                    .limit(6)
                    .toList()
                    : List.of();
            if (!failedChecks.isEmpty()) {
                html.append("      <div style=\"margin-top:10px\">\n");
                for (ReviewAssuranceSummaryDTO.AssuranceCheck check : failedChecks) {
                    html.append("        <span class=\"trace-chip\">")
                            .append(escapeHtml(check.getId()))
                            .append(": ")
                            .append(escapeHtml(check.getReason()))
                            .append("</span>\n");
                }
                html.append("      </div>\n");
            }
        }
        html.append("    </div>\n");
        return html.toString();
    }

}
