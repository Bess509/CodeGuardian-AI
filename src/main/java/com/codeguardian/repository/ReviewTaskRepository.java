package com.codeguardian.repository;

import com.codeguardian.entity.ReviewTask;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 审查任务仓储接口
 */
@Repository
public interface ReviewTaskRepository extends JpaRepository<ReviewTask, Long> {
    
    /**
     * 根据状态查询任务
     */
    List<ReviewTask> findByStatus(Integer status);

    Page<ReviewTask> findBySessionId(Long sessionId, Pageable pageable);

    Page<ReviewTask> findByProjectKey(String projectKey, Pageable pageable);
    
    /**
     * 根据审查类型查询任务
     */
    Page<ReviewTask> findByReviewType(Integer reviewType, Pageable pageable);
    
    /**
     * 根据名称模糊查询
     */
    @Query("SELECT t FROM ReviewTask t WHERE t.name LIKE %:name%")
    Page<ReviewTask> findByNameContaining(@Param("name") String name, Pageable pageable);
    
    /**
     * 根据时间范围查询
     */
    @Query("SELECT t FROM ReviewTask t WHERE t.createdAt BETWEEN :startTime AND :endTime")
    Page<ReviewTask> findByCreatedAtBetween(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            Pageable pageable
    );
    
    /**
     * 综合查询：名称、类型、时间范围
     */
    @Query("SELECT t FROM ReviewTask t WHERE " +
           "(:name IS NULL OR t.name LIKE %:name%) AND " +
           "(:reviewType IS NULL OR t.reviewType = :reviewType) AND " +
           "(:status IS NULL OR t.status = :status) AND " +
           "(t.createdAt >= COALESCE(:startTime, t.createdAt)) AND " +
           "(t.createdAt <= COALESCE(:endTime, t.createdAt))")
    Page<ReviewTask> findByConditions(
            @Param("name") String name,
            @Param("reviewType") Integer reviewType,
            @Param("status") Integer status,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            Pageable pageable
    );
    
    /**
     * 根据任务ID查询（包含findings）
     * @deprecated use findById instead
     */
    default Optional<ReviewTask> findByIdWithFindings(@Param("id") Long id) {
        return findById(id);
    }

    /**
     * 获取最新完成的任务
     */
    Optional<ReviewTask> findTopByStatusOrderByCreatedAtDesc(Integer status);

    /**
     * 获取最近完成的前5个任务
     */
    List<ReviewTask> findTop5ByStatusOrderByCreatedAtDesc(Integer status);

    /**
     * 获取最近完成的前5个项目类型任务
     */
    List<ReviewTask> findTop5ByStatusAndReviewTypeOrderByCreatedAtDesc(Integer status, Integer reviewType);

    /**
     * 获取最近完成的项目类型任务（按项目名称去重，取最新）
     */
    @Query("SELECT t FROM ReviewTask t WHERE t.id IN (" +
           "  SELECT MAX(rt.id) FROM ReviewTask rt " +
           "  WHERE rt.status = :status AND rt.reviewType = :reviewType " +
           "  GROUP BY rt.name" +
           ") ORDER BY t.createdAt DESC")
    List<ReviewTask> findLatestProjectTasks(@Param("status") Integer status, @Param("reviewType") Integer reviewType, Pageable pageable);
}
