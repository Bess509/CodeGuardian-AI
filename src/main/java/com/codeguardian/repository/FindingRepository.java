package com.codeguardian.repository;

import com.codeguardian.entity.Finding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 审查发现仓储接口
 */
@Repository
public interface FindingRepository extends JpaRepository<Finding, Long> {
    
    /**
     * 根据任务ID查询所有发现
     */
    List<Finding> findByTaskId(Long taskId);
    
    /**
     * 根据任务ID和严重程度查询
     */
    List<Finding> findByTaskIdAndSeverity(Long taskId, Integer severity);
    
    /**
     * 根据任务ID和类别代码查询
     */
    List<Finding> findByTaskIdAndCategory(Long taskId, String category);
    
    /**
     * 统计任务的问题数量
     */
    @Query("SELECT COUNT(f) FROM Finding f WHERE f.taskId = :taskId")
    Long countByTaskId(@Param("taskId") Long taskId);
    
    /**
     * 统计任务的严重问题数量
     */
    @Query("SELECT COUNT(f) FROM Finding f WHERE f.taskId = :taskId AND f.severity = :severity")
    Long countByTaskIdAndSeverity(@Param("taskId") Long taskId, @Param("severity") Integer severity);
}
