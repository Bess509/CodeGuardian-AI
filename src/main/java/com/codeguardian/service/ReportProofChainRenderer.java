package com.codeguardian.service;

import static com.codeguardian.service.ReportTextFormatter.escapeHtml;
import static com.codeguardian.service.ReportTextFormatter.shortHash;

import com.codeguardian.dto.ReviewGroundingPolicyDTO;
import com.codeguardian.dto.ReviewProofBundleDTO;
import com.codeguardian.dto.ReviewProofBundleVerificationDTO;
import com.codeguardian.dto.ReviewRuntimeManifestDTO;
import com.codeguardian.service.provenance.ReviewAuditService;

final class ReportProofChainRenderer {

    String buildProofChainVisualization(ReviewAuditService.IntegrityResult integrity,
                                        ReviewProofBundleDTO proofBundle,
                                        ReviewProofBundleVerificationDTO verification,
                                        ReviewGroundingPolicyDTO groundingPolicy,
                                        ReviewRuntimeManifestDTO runtimeManifest,
                                        ReviewRuntimeManifestDTO.DatabaseGuardSnapshot databaseGuards,
                                        int evidenceCount,
                                        long sourceCount,
                                        long ragCount) {
        boolean evidenceValid = verification != null && Boolean.TRUE.equals(verification.getEvidenceHashValid());
        boolean groundingValid = verification != null && Boolean.TRUE.equals(verification.getGroundingPolicyValid());
        boolean auditValid = integrity != null
                && integrity.isValid()
                && integrity.isAuditCoverageValid()
                && integrity.isAuditOrderValid()
                && integrity.isSignatureValid();
        boolean runtimeManifestValid = verification != null && Boolean.TRUE.equals(verification.getRuntimeManifestHashValid());
        Boolean guardInstalled = databaseGuards != null ? databaseGuards.getAppendOnlyGuardsInstalled() : null;
        boolean dbGuardValid = Boolean.TRUE.equals(guardInstalled)
                && Boolean.TRUE.equals(databaseGuards.getUpdatesBlocked())
                && Boolean.TRUE.equals(databaseGuards.getDeletesBlocked());
        Boolean currentStateMatch = verification != null ? verification.getCurrentStateMatch() : null;
        boolean proofValid = verification != null && Boolean.TRUE.equals(verification.getValid());
        boolean auditCoverageValid = integrity != null && integrity.isAuditCoverageValid();
        int missingAuditEventCount = integrity != null && integrity.getMissingEventTypes() != null
                ? integrity.getMissingEventTypes().size()
                : 0;
        boolean auditOrderValid = integrity != null && integrity.isAuditOrderValid();
        int auditOrderViolationCount = integrity != null && integrity.getAuditOrderViolations() != null
                ? integrity.getAuditOrderViolations().size()
                : 0;

        StringBuilder html = new StringBuilder();
        html.append("    <div class=\"proof-chain\">\n");
        html.append("      <div class=\"proof-chain-title\">证明链</div>\n");
        html.append("      <div class=\"proof-chain-track\">\n");
        html.append(proofNode("证据", evidenceValid ? "已哈希" : "异常",
                "记录=" + evidenceCount + " 源码=" + sourceCount + " RAG=" + ragCount,
                evidenceValid ? "ok" : "bad"));
        html.append(proofNode("问题溯源", groundingValid ? "策略通过" : "已阻断",
                "违规=" + (verification != null && verification.getGroundingViolationCount() != null
                        ? verification.getGroundingViolationCount()
                        : groundingPolicy != null && groundingPolicy.getViolationCount() != null
                        ? groundingPolicy.getViolationCount() : 0),
                groundingValid ? "ok" : "bad"));
        html.append(proofNode("审计轨迹", auditValid ? "有效" : "异常",
                "事件=" + (integrity != null ? integrity.getEventCount() : 0)
                        + " 已签名=" + (integrity != null ? integrity.getSignedEventCount() : 0)
                        + " 覆盖=" + (auditCoverageValid ? "有效" : "缺失")
                        + " 缺失=" + missingAuditEventCount
                        + " 顺序=" + (auditOrderValid ? "有效" : "异常")
                        + " 顺序违规=" + auditOrderViolationCount,
                auditValid ? "ok" : "bad"));
        html.append(proofNode("运行保护", runtimeGuardStatus(runtimeManifestValid, guardInstalled, dbGuardValid),
                "清单=" + shortHash(runtimeManifest != null ? runtimeManifest.getManifestHash() : null)
                        + " 数据库=" + (databaseGuards != null ? databaseGuards.getVerificationReason() : "缺失"),
                runtimeGuardClass(runtimeManifestValid, guardInstalled, dbGuardValid)));
        html.append(proofNode("当前状态", currentStateLabel(currentStateMatch),
                "状态=" + shortHash(proofBundle != null ? proofBundle.getReviewStateHash() : null),
                currentStateClass(currentStateMatch)));
        html.append(proofNode("证明包", proofValid ? "已证明" : "未证明",
                "证明包=" + shortHash(proofBundle != null ? proofBundle.getBundleHash() : null),
                proofValid ? "ok" : "bad"));
        html.append("      </div>\n");
        html.append("    </div>\n");
        return html.toString();
    }

    private String proofNode(String title, String status, String detail, String statusClass) {
        return "        <div class=\"proof-node " + escapeHtml(statusClass) + "\">"
                + "<div class=\"proof-node-title\">" + escapeHtml(title) + "</div>"
                + "<div class=\"proof-node-status\">" + escapeHtml(status) + "</div>"
                + "<div class=\"proof-node-detail\">" + escapeHtml(detail) + "</div>"
                + "</div>\n";
    }

    private String runtimeGuardStatus(boolean runtimeManifestValid, Boolean guardInstalled, boolean dbGuardValid) {
        if (!runtimeManifestValid) {
            return "清单异常";
        }
        if (guardInstalled == null) {
            return "保护未知";
        }
        return dbGuardValid ? "已保护" : "保护缺失";
    }

    private String runtimeGuardClass(boolean runtimeManifestValid, Boolean guardInstalled, boolean dbGuardValid) {
        if (!runtimeManifestValid || Boolean.FALSE.equals(guardInstalled)) {
            return "bad";
        }
        return dbGuardValid ? "ok" : "warn";
    }

    private String currentStateLabel(Boolean currentStateMatch) {
        if (currentStateMatch == null) {
            return "未知";
        }
        return Boolean.TRUE.equals(currentStateMatch) ? "当前一致" : "已漂移";
    }

    private String currentStateClass(Boolean currentStateMatch) {
        if (currentStateMatch == null) {
            return "warn";
        }
        return Boolean.TRUE.equals(currentStateMatch) ? "ok" : "bad";
    }
}
