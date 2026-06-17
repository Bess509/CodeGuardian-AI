package com.codeguardian.service;

import com.codeguardian.dto.DashboardDTO;
import com.codeguardian.entity.Finding;
import com.codeguardian.entity.ReviewTask;
import com.codeguardian.repository.FindingRepository;
import com.codeguardian.repository.ReviewTaskRepository;
import com.codeguardian.service.integration.QualityJudgeResult;
import com.codeguardian.service.integration.QualityJudgeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final ReviewTaskRepository reviewTaskRepository;
    private final FindingRepository findingRepository;
    private final SystemConfigService systemConfigService;
    private final ObjectMapper objectMapper;
    private final QualityJudgeService qualityJudgeService;

    /**
     * 计算并生成最新的仪表盘数据
     *
     * @return 最新的仪表盘数据
     */
    @Transactional(readOnly = true)
    public DashboardDTO computeDashboardData() {
        Optional<ReviewTask> latestTaskOpt = reviewTaskRepository.findTopByStatusOrderByCreatedAtDesc(com.codeguardian.enums.TaskStatusEnum.COMPLETED.getValue());
        
        int healthScore = 100;
        DashboardDTO.SeverityDistribution distribution = DashboardDTO.SeverityDistribution.builder()
                .critical(0).high(0).medium(0).low(0)
                .build();

        if (latestTaskOpt.isPresent()) {
            ReviewTask latestTask = latestTaskOpt.get();
            List<Finding> findings = findingRepository.findByTaskId(latestTask.getId());
            
            for (Finding f : findings) {
                Integer severity = f.getSeverity();
                if (severity == null) continue;
                if (severity.equals(com.codeguardian.enums.SeverityEnum.CRITICAL.getValue())) distribution.setCritical(distribution.getCritical() + 1);
                else if (severity.equals(com.codeguardian.enums.SeverityEnum.HIGH.getValue())) distribution.setHigh(distribution.getHigh() + 1);
                else if (severity.equals(com.codeguardian.enums.SeverityEnum.MEDIUM.getValue())) distribution.setMedium(distribution.getMedium() + 1);
                else distribution.setLow(distribution.getLow() + 1);
            }
            
            healthScore = calculateHealthScore(distribution);
        }

        // 获取最近5个完成的项目类型任务用于项目统计（每个项目只取最新的）
        List<ReviewTask> recentTasks = reviewTaskRepository
                .findLatestProjectTasks(com.codeguardian.enums.TaskStatusEnum.COMPLETED.getValue(), com.codeguardian.enums.ReviewTypeEnum.PROJECT.getValue(), org.springframework.data.domain.PageRequest.of(0, 5));
        List<DashboardDTO.ProjectStatDTO> projectStats = recentTasks.stream()
                .map(this::convertToProjectStat)
                .collect(Collectors.toList());
        
        // 反转列表，使图表从左到右显示时间顺序（旧 -> 新）
        Collections.reverse(projectStats);

        DashboardDTO.QualityJudgeDTO qualityJudge = latestTaskOpt
                .map(task -> qualityJudgeService.evaluate(findingRepository.findByTaskId(task.getId())))
                .map(this::qualityJudge)
                .orElseGet(() -> qualityJudge(qualityJudgeService.evaluate(List.of())));

        return DashboardDTO.builder()
                .healthScore(healthScore)
                .problemDistribution(distribution)
                .projectStats(projectStats)
                .qualityJudge(qualityJudge)
                .build();
    }

    /**
     * 根据严重程度分布计算代码健康分
     *
     * @param distribution 严重程度分布
     * @return 0-100 的健康分
     */
    private int calculateHealthScore(DashboardDTO.SeverityDistribution distribution) {
        int penalty = distribution.getCritical() * 10
                + distribution.getHigh() * 5
                + distribution.getMedium() * 2
                + distribution.getLow() * 1;
        return Math.max(0, 100 - penalty);
    }

    /**
     * 获取缓存的仪表盘数据；若不存在则实时计算并返回
     *
     * @return 仪表盘数据
     */
    public DashboardDTO getCachedDashboardData() {
        try {
            String json = systemConfigService.getConfigValue(SystemConfigService.KEY_METRICS_DASHBOARD);
            if (json != null && !json.isEmpty()) {
                return objectMapper.readValue(json, DashboardDTO.class);
            }
        } catch (Exception ignored) {
        }
        return computeDashboardData();
    }

    private DashboardDTO.ProjectStatDTO convertToProjectStat(ReviewTask task) {
        List<Finding> findings = findingRepository.findByTaskId(task.getId());
        
        int critical = 0, high = 0, medium = 0, low = 0;
        for (Finding f : findings) {
            Integer severity = f.getSeverity();
            if (severity == null) continue;
            if (severity.equals(com.codeguardian.enums.SeverityEnum.CRITICAL.getValue())) critical++;
            else if (severity.equals(com.codeguardian.enums.SeverityEnum.HIGH.getValue())) high++;
            else if (severity.equals(com.codeguardian.enums.SeverityEnum.MEDIUM.getValue())) medium++;
            else low++;
        }
        return DashboardDTO.ProjectStatDTO.builder()
                .projectName(task.getName())
                .criticalCount(critical)
                .highCount(high)
                .mediumCount(medium)
                .lowCount(low)
                .totalCount(findings != null ? findings.size() : 0)
                .build();
    }

    private DashboardDTO.QualityJudgeDTO qualityJudge(QualityJudgeResult result) {
        return DashboardDTO.QualityJudgeDTO.builder()
                .score(result.getScore())
                .grade(result.getGrade())
                .reason(result.getReason())
                .duplicateRate(result.getDuplicateRate())
                .evidenceMissingRate(result.getEvidenceMissingRate())
                .highRiskGroundingRate(result.getHighRiskGroundingRate())
                .p1CoverageRate(result.getP1CoverageRate())
                .ragHitRate(result.getRagHitRate())
                .build();
    }
}
