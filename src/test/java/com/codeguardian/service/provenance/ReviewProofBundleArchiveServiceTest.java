package com.codeguardian.service.provenance;

import com.codeguardian.dto.ReviewProofBundleArchiveDTO;
import com.codeguardian.dto.ReviewProofBundleDTO;
import com.codeguardian.dto.ReviewProofBundleVerificationDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReviewProofBundleArchiveServiceTest {

    @Test
    void should_build_downloadable_archive_with_content_hash_and_current_verification() {
        ReviewProofBundleService proofBundleService = mock(ReviewProofBundleService.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ProvenanceHashService hashService = new ProvenanceHashService(objectMapper);
        ReviewProofBundleArchiveService service = new ReviewProofBundleArchiveService(
                proofBundleService,
                hashService,
                objectMapper
        );
        ReviewProofBundleDTO bundle = ReviewProofBundleDTO.builder()
                .schemaVersion(ReviewProofBundleService.SCHEMA_VERSION)
                .generatedAt(LocalDateTime.of(2026, 6, 9, 12, 0))
                .reviewStateHashAlgorithm("SHA-256")
                .reviewStateHash("r".repeat(64))
                .bundleHashAlgorithm("SHA-256")
                .bundleHash("b".repeat(64))
                .bundleSignatureKeyId("bundle-key")
                .bundleSignatureAlgorithm("HmacSHA256")
                .bundleSignature("s".repeat(64))
                .task(ReviewProofBundleDTO.TaskSnapshot.builder()
                        .id(7L)
                        .name("payment review")
                        .build())
                .counts(ReviewProofBundleDTO.Counts.builder()
                        .auditEventCount(1)
                        .evidenceCount(1)
                        .findingCount(1)
                        .groundedFindingCount(1L)
                        .signedAuditEventCount(1)
                        .groundingViolationCount(0)
                        .build())
                .build();
        ReviewProofBundleVerificationDTO verification = ReviewProofBundleVerificationDTO.builder()
                .valid(true)
                .reason("ok")
                .currentStateMatch(true)
                .build();

        when(proofBundleService.buildBundle(7L)).thenReturn(bundle);
        when(proofBundleService.verifyBundleAgainstCurrentState(7L, bundle)).thenReturn(verification);

        ReviewProofBundleArchiveDTO archive = service.buildArchive(7L);

        assertEquals(ReviewProofBundleArchiveService.SCHEMA_VERSION, archive.getSchemaVersion());
        assertEquals(7L, archive.getTaskId());
        assertEquals(ReviewProofBundleArchiveService.MEDIA_TYPE, archive.getMediaType());
        assertEquals("UTF-8", archive.getContentEncoding());
        assertEquals("SHA-256", archive.getContentHashAlgorithm());
        assertEquals("codeguardian-proof-task-7-bbbbbbbbbbbb.json", archive.getFileName());
        assertEquals("b".repeat(64), archive.getBundleHash());
        assertEquals("r".repeat(64), archive.getReviewStateHash());
        assertEquals("bundle-key", archive.getBundleSignatureKeyId());
        assertEquals("HmacSHA256", archive.getBundleSignatureAlgorithm());
        assertEquals(Boolean.TRUE, archive.getVerificationValid());
        assertEquals("ok", archive.getVerificationReason());
        assertEquals(Boolean.TRUE, archive.getCurrentStateMatch());
        assertEquals((long) archive.contentBytes().length, archive.getContentLength());
        assertEquals(hashService.sha256Hex(archive.getContent()), archive.getContentHash());
        assertTrue(archive.getContent().contains("\"bundleHash\""));
        assertTrue(archive.getContent().contains("b".repeat(64)));
        verify(proofBundleService).verifyBundleAgainstCurrentState(7L, bundle);
    }
}
