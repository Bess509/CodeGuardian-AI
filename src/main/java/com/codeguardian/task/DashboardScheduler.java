package com.codeguardian.task;

import com.codeguardian.dto.DashboardDTO;
import com.codeguardian.service.DashboardService;
import com.codeguardian.service.SystemConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 仪表盘数据定时统计任务
 *
 * <p>周期性计算系统的仪表盘聚合数据，并缓存至系统配置中，避免页面实时计算造成的性能压力。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DashboardScheduler {

    private final DashboardService dashboardService;
    private final SystemConfigService systemConfigService;
    private final ObjectMapper objectMapper;

    /**
     * 周期性刷新仪表盘数据缓存
     *
     * <p>默认每 5 分钟执行一次，可通过配置项 `dashboard.refresh.interval.ms` 覆盖。</p>
     */
    @Scheduled(fixedDelayString = "${dashboard.refresh.interval.ms:300000}")
    public void refreshDashboardCache() {
        try {
            log.info("Dashboard metrics cache start");
            DashboardDTO dto = dashboardService.computeDashboardData();
            String json = objectMapper.writeValueAsString(dto);
            systemConfigService.saveRawConfig(
                    SystemConfigService.KEY_METRICS_DASHBOARD,
                    json,
                    "Metrics",
                    "Dashboard aggregated metrics cache"
            );
            log.info("Dashboard metrics cache updated");
        } catch (Exception e) {
            log.warn("Failed to refresh dashboard cache: {}", e.getMessage());
        }
    }
}

