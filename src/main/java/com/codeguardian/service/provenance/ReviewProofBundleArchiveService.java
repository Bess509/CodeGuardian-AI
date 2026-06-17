package com.codeguardian.service.provenance;

import com.codeguardian.dto.ReviewProofBundleArchiveDTO;
import com.codeguardian.dto.ReviewProofBundleDTO;
import com.codeguardian.dto.ReviewProofBundleVerificationDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ReviewProofBundleArchiveService {

    public static final String SCHEMA_VERSION = "codeguardian-proof-bundle-archive-v1";
    public static final String MEDIA_TYPE = "application/vnd.codeguardian.review-proof-bundle+json";
    public static final String CONTENT_ENCODING = StandardCharsets.UTF_8.name();

    private final ReviewProofBundleService proofBundleService;
    private final ProvenanceHashService hashService;
    private final ObjectMapper objectMapper;

    public ReviewProofBundleArchiveDTO buildArchive(Long taskId) {
        ReviewProofBundleDTO bundle = proofBundleService.buildBundle(taskId);
        ReviewProofBundleVerificationDTO verification = proofBundleService.verifyBundleAgainstCurrentState(taskId, bundle);
        String content = serialize(bundle);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        return ReviewProofBundleArchiveDTO.builder()
                .schemaVersion(SCHEMA_VERSION)
                .generatedAt(LocalDateTime.now())
                .taskId(taskId)
                .fileName(fileName(taskId, bundle.getBundleHash()))
                .mediaType(MEDIA_TYPE)
                .contentEncoding(CONTENT_ENCODING)
                .contentHashAlgorithm("SHA-256")
                .contentHash(hashService.sha256Hex(content))
                .contentLength((long) bytes.length)
                .bundleHash(bundle.getBundleHash())
                .reviewStateHash(bundle.getReviewStateHash())
                .bundleSignatureKeyId(bundle.getBundleSignatureKeyId())
                .bundleSignatureAlgorithm(bundle.getBundleSignatureAlgorithm())
                .verificationValid(verification.getValid())
                .verificationReason(verification.getReason())
                .currentStateMatch(verification.getCurrentStateMatch())
                .content(content)
                .build();
    }

    private String serialize(ReviewProofBundleDTO bundle) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(bundle);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize proof bundle archive", e);
        }
    }

    private String fileName(Long taskId, String bundleHash) {
        String hashPrefix = bundleHash != null && bundleHash.length() >= 12
                ? bundleHash.substring(0, 12)
                : "unhashed";
        return "codeguardian-proof-task-" + taskId + "-" + hashPrefix + ".json";
    }
}
