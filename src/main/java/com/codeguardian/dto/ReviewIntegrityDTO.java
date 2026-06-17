package com.codeguardian.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewIntegrityDTO {
    private Long taskId;
    private Boolean auditChainValid;
    private Integer auditEventCount;
    private Long failedEventId;
    private String reason;
    private String lastAuditHash;
    private Boolean auditSignatureValid;
    private Integer signedAuditEventCount;
    private String signatureKeyId;
    private Boolean auditCoverageValid;
    private List<String> missingAuditEventTypes;
    private Boolean auditTerminalEventConsistent;
    private Boolean auditOrderValid;
    private List<String> auditOrderViolations;
    private Long evidenceCount;
    private Long groundedFindingCount;
    private Long totalFindingCount;
    private Boolean groundingPolicyValid;
    private String groundingPolicyVersion;
    private String groundingPolicyReason;
    private Integer groundingViolationCount;
    private String runtimeManifestVersion;
    private String runtimeManifestHash;
}
