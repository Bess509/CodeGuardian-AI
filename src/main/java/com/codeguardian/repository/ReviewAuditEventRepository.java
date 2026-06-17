package com.codeguardian.repository;

import com.codeguardian.entity.ReviewAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ReviewAuditEventRepository extends JpaRepository<ReviewAuditEvent, Long> {

    Optional<ReviewAuditEvent> findTopByTaskIdOrderByIdDesc(Long taskId);

    List<ReviewAuditEvent> findByTaskIdOrderByIdAsc(Long taskId);

    long countByTaskId(Long taskId);
}
