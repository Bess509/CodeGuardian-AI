package com.codeguardian.service;

import com.codeguardian.dto.ReviewAssuranceSummaryDTO;
import com.codeguardian.dto.ReviewGroundingPolicyDTO;
import com.codeguardian.dto.ReviewProofBundleDTO;
import com.codeguardian.dto.ReviewProofBundleVerificationDTO;
import com.codeguardian.dto.ReviewRuntimeManifestDTO;
import com.codeguardian.entity.ReviewReport;
import com.codeguardian.entity.ReviewTask;
import com.codeguardian.enums.ReviewTypeEnum;
import com.codeguardian.enums.TaskStatusEnum;
import com.codeguardian.model.dto.SettingsDTO;
import com.codeguardian.repository.FindingRepository;
import com.codeguardian.repository.ReviewEvidenceRepository;
import com.codeguardian.repository.ReviewReportRepository;
import com.codeguardian.repository.ReviewTaskRepository;
import com.codeguardian.service.provenance.ReviewAuditService;
import com.codeguardian.service.provenance.ReviewAssuranceService;
import com.codeguardian.service.provenance.ReviewProofBundleService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportServiceTest {

    @Test
    void should_reuse_report_when_proof_state_hash_matches_current_bundle() {
        Fixtures fixtures = fixtures();
        ReviewTask task = completedTask();
        ReviewProofBundleDTO bundle = proofBundle("state-1", "bundle-1");
        ReviewProofBundleVerificationDTO verification = verification(true, "ok");
        ReviewReport existing = ReviewReport.builder()
                .taskId(7L)
                .reviewStateHash("state-1")
                .proofBundleHash("bundle-1")
                .proofBundleValid(true)
                .proofBundleReason("ok")
                .htmlContent("cached")
                .build();

        when(fixtures.taskRepository.findById(7L)).thenReturn(Optional.of(task));
        when(fixtures.proofBundleService.buildBundle(7L)).thenReturn(bundle);
        when(fixtures.proofBundleService.verifyBundleAgainstCurrentState(7L, bundle)).thenReturn(verification);
        when(fixtures.reportRepository.findByTaskId(7L)).thenReturn(Optional.of(existing));

        ReviewReport report = fixtures.service.generateReport(7L);

        assertSame(existing, report);
        verify(fixtures.reportRepository, never()).save(any(ReviewReport.class));
    }

    @Test
    void should_update_existing_report_when_review_state_hash_changes() {
        Fixtures fixtures = fixtures();
        ReviewTask task = completedTask();
        ReviewProofBundleDTO bundle = proofBundle("state-2", "bundle-2");
        ReviewProofBundleVerificationDTO verification = verification(true, "ok");
        ReviewReport existing = ReviewReport.builder()
                .taskId(7L)
                .reviewStateHash("state-1")
                .proofBundleHash("bundle-1")
                .proofBundleValid(true)
                .proofBundleReason("ok")
                .htmlContent("old")
                .build();

        SettingsDTO settings = new SettingsDTO();
        settings.setMaxIssues(10);
        when(fixtures.taskRepository.findById(7L)).thenReturn(Optional.of(task));
        when(fixtures.proofBundleService.buildBundle(7L)).thenReturn(bundle);
        when(fixtures.proofBundleService.verifyBundleAgainstCurrentState(7L, bundle)).thenReturn(verification);
        when(fixtures.reportRepository.findByTaskId(7L)).thenReturn(Optional.of(existing));
        when(fixtures.findingRepository.findByTaskId(7L)).thenReturn(List.of());
        when(fixtures.evidenceRepository.countByTaskId(7L)).thenReturn(0L);
        when(fixtures.evidenceRepository.findByTaskIdOrderByCreatedAtAscIdAsc(7L)).thenReturn(List.of());
        when(fixtures.systemConfigService.getSettings()).thenReturn(settings);
        when(fixtures.auditService.verifyTaskChain(7L, TaskStatusEnum.COMPLETED.getValue()))
                .thenReturn(ReviewAuditService.IntegrityResult.builder()
                        .valid(true)
                        .eventCount(3)
                        .reason("ok")
                        .lastHash("a".repeat(64))
                        .signatureValid(true)
                        .signedEventCount(0)
                        .auditCoverageValid(true)
                        .missingEventTypes(List.of())
                        .terminalEventConsistent(true)
                        .auditOrderValid(true)
                        .auditOrderViolations(List.of())
                        .build());
        when(fixtures.assuranceService.buildSummary(7L)).thenReturn(assuranceSummary());
        when(fixtures.reportRepository.save(any(ReviewReport.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ReviewReport report = fixtures.service.generateReport(7L);

        assertSame(existing, report);
        assertEquals("state-2", report.getReviewStateHash());
        assertEquals("bundle-2", report.getProofBundleHash());
        assertEquals(Boolean.TRUE, report.getProofBundleValid());
        assertEquals("ok", report.getProofBundleReason());
        assertTrue(report.getHtmlContent().contains("审查状态"));
        assertTrue(report.getHtmlContent().contains("证明结论"));
        assertTrue(report.getHtmlContent().contains("证明链"));
        assertTrue(report.getHtmlContent().contains("覆盖=有效"));
        assertTrue(report.getHtmlContent().contains("顺序=有效"));
        assertTrue(report.getHtmlContent().contains("缺失=0"));
        assertTrue(report.getHtmlContent().contains("运行保护"));
        assertTrue(report.getHtmlContent().contains("当前一致"));
        assertTrue(report.getHtmlContent().contains("已证明"));
        assertTrue(report.getHtmlContent().contains("数据库只追加保护"));
        assertTrue(report.getHtmlContent().contains("已安装"));
        assertTrue(report.getHtmlContent().contains("/api/review/task/7/assurance-summary"));
        assertTrue(report.getHtmlContent().contains("/api/review/task/7/proof-bundle/archive"));
        verify(fixtures.reportRepository).save(existing);
    }

    private Fixtures fixtures() {
        ReviewTaskRepository taskRepository = mock(ReviewTaskRepository.class);
        ReviewReportRepository reportRepository = mock(ReviewReportRepository.class);
        FindingRepository findingRepository = mock(FindingRepository.class);
        ReviewEvidenceRepository evidenceRepository = mock(ReviewEvidenceRepository.class);
        CodeParserService codeParserService = mock(CodeParserService.class);
        SystemConfigService systemConfigService = mock(SystemConfigService.class);
        ReviewAuditService auditService = mock(ReviewAuditService.class);
        ReviewProofBundleService proofBundleService = mock(ReviewProofBundleService.class);
        ReviewAssuranceService assuranceService = mock(ReviewAssuranceService.class);
        return new Fixtures(
                taskRepository,
                reportRepository,
                findingRepository,
                evidenceRepository,
                systemConfigService,
                auditService,
                proofBundleService,
                assuranceService,
                new ReportService(
                        taskRepository,
                        reportRepository,
                        findingRepository,
                        evidenceRepository,
                        codeParserService,
                        systemConfigService,
                        auditService,
                        proofBundleService,
                        assuranceService
                )
        );
    }

    private ReviewTask completedTask() {
        return ReviewTask.builder()
                .id(7L)
                .name("report task")
                .reviewType(ReviewTypeEnum.SNIPPET.getValue())
                .scope("class Demo {}")
                .status(TaskStatusEnum.COMPLETED.getValue())
                .createdAt(LocalDateTime.of(2026, 6, 9, 12, 0))
                .completedAt(LocalDateTime.of(2026, 6, 9, 12, 1))
                .build();
    }

    private ReviewProofBundleDTO proofBundle(String reviewStateHash, String bundleHash) {
        return ReviewProofBundleDTO.builder()
                .reviewStateHash(reviewStateHash)
                .bundleHash(bundleHash)
                .runtimeManifest(ReviewRuntimeManifestDTO.builder()
                        .manifestHash("m".repeat(64))
                        .databaseGuards(ReviewRuntimeManifestDTO.DatabaseGuardSnapshot.builder()
                                .querySupported(true)
                                .appendOnlyGuardsInstalled(true)
                                .expectedTriggerCount(2)
                                .installedTriggerCount(2)
                                .updatesBlocked(true)
                                .deletesBlocked(true)
                                .verificationReason("ok")
                                .build())
                        .build())
                .groundingPolicy(ReviewGroundingPolicyDTO.builder()
                        .minSeverity("HIGH")
                        .violationCount(0)
                        .invalidSourceAnchorCount(0)
                        .build())
                .build();
    }

    private ReviewProofBundleVerificationDTO verification(Boolean valid, String reason) {
        return ReviewProofBundleVerificationDTO.builder()
                .valid(valid)
                .reason(reason)
                .evidenceHashValid(true)
                .reviewStateHashValid(true)
                .runtimeManifestHashValid(true)
                .groundingPolicyValid(true)
                .auditOrderValid(true)
                .bundleHashValid(true)
                .bundleSignatureValid(true)
                .currentStateMatch(true)
                .currentReviewStateMatch(true)
                .currentRuntimeManifestMatch(true)
                .currentBundleMatch(true)
                .invalidEvidenceCount(0)
                .groundingViolationCount(0)
                .build();
    }

    private ReviewAssuranceSummaryDTO assuranceSummary() {
        return ReviewAssuranceSummaryDTO.builder()
                .verdict("PROVEN")
                .valid(true)
                .reason("ok")
                .reviewStateHash("state-2")
                .bundleHash("bundle-2")
                .runtimeManifestHash("m".repeat(64))
                .knowledgeBaseFingerprint("kb-hash")
                .counts(ReviewAssuranceSummaryDTO.AssuranceCounts.builder()
                        .highRiskFindingCount(0)
                        .groundedHighRiskFindingCount(0)
                        .invalidEvidenceCount(0)
                        .build())
                .checks(List.of())
                .build();
    }

    private record Fixtures(ReviewTaskRepository taskRepository,
                            ReviewReportRepository reportRepository,
                            FindingRepository findingRepository,
                            ReviewEvidenceRepository evidenceRepository,
                            SystemConfigService systemConfigService,
                            ReviewAuditService auditService,
                            ReviewProofBundleService proofBundleService,
                            ReviewAssuranceService assuranceService,
                            ReportService service) {
    }
}
