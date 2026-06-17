package com.codeguardian.dto.integration;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CodeGuardianProjectConfig {
    private String blockOn;
    private Boolean requireGrounding;
    private Boolean requireProofBundle;
    private Boolean requireAuditChain;
    private String ragMode;
    private Boolean diffOnly;
    private List<String> includePaths;
    private List<String> excludePaths;
    private String sourcePath;

    public boolean isEmpty() {
        return blockOn == null
                && requireGrounding == null
                && requireProofBundle == null
                && requireAuditChain == null
                && ragMode == null
                && diffOnly == null
                && (includePaths == null || includePaths.isEmpty())
                && (excludePaths == null || excludePaths.isEmpty());
    }
}
