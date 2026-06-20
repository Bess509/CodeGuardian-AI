package com.codeguardian.repository;

import com.codeguardian.entity.ReviewSessionMemory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReviewSessionMemoryRepository extends JpaRepository<ReviewSessionMemory, Long> {

    @Query("SELECT m FROM ReviewSessionMemory m WHERE " +
            "m.userId = :userId AND " +
            "m.projectKey = :projectKey AND " +
            "m.status = :status")
    List<ReviewSessionMemory> findRecallCandidates(@Param("userId") Long userId,
                                                    @Param("projectKey") String projectKey,
                                                    @Param("status") String status);

    List<ReviewSessionMemory> findBySessionIdOrderByCreatedAtDesc(Long sessionId);
}
