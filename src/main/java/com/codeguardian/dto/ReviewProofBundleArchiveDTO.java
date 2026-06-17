package com.codeguardian.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewProofBundleArchiveDTO {
    private String schemaVersion;
    private LocalDateTime generatedAt;
    private Long taskId;
    private String fileName;
    private String mediaType;
    private String contentEncoding;
    private String contentHashAlgorithm;
    private String contentHash;
    private Long contentLength;
    private String bundleHash;
    private String reviewStateHash;
    private String bundleSignatureKeyId;
    private String bundleSignatureAlgorithm;
    private Boolean verificationValid;
    private String verificationReason;
    private Boolean currentStateMatch;
    private String content;

    public byte[] contentBytes() {
        return (content != null ? content : "").getBytes(StandardCharsets.UTF_8);
    }
}
