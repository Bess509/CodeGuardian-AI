package com.codeguardian.repository;

import com.codeguardian.entity.ReviewSessionMessage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReviewSessionMessageRepository extends JpaRepository<ReviewSessionMessage, Long> {

    @Query("SELECT m FROM ReviewSessionMessage m WHERE m.sessionId = :sessionId ORDER BY m.createdAt DESC")
    List<ReviewSessionMessage> findRecentBySessionId(@Param("sessionId") Long sessionId, Pageable pageable);

    Page<ReviewSessionMessage> findBySessionIdOrderByCreatedAtAsc(Long sessionId, Pageable pageable);
}
