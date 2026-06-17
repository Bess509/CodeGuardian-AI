package com.codeguardian.repository;

import com.codeguardian.entity.ReviewReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 审查报告仓储接口
 */
@Repository
public interface ReviewReportRepository extends JpaRepository<ReviewReport, Long> {
    
    /**
     * 根据任务ID查询报告
     */
    Optional<ReviewReport> findByTaskId(Long taskId);
}

