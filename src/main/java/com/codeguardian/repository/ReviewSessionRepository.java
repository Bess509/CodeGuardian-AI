package com.codeguardian.repository;

import com.codeguardian.entity.ReviewSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ReviewSessionRepository extends JpaRepository<ReviewSession, Long> {
    Optional<ReviewSession> findByIdAndUserId(Long id, Long userId);

    Page<ReviewSession> findByUserId(Long userId, Pageable pageable);

    Page<ReviewSession> findByUserIdAndProjectKey(Long userId, String projectKey, Pageable pageable);
}
