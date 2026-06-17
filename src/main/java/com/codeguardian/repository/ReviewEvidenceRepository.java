package com.codeguardian.repository;

import com.codeguardian.entity.ReviewEvidence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReviewEvidenceRepository extends JpaRepository<ReviewEvidence, Long> {

    List<ReviewEvidence> findByTaskIdOrderByCreatedAtAscIdAsc(Long taskId);

    List<ReviewEvidence> findByFindingIdOrderByCreatedAtAscIdAsc(Long findingId);

    List<ReviewEvidence> findByTaskIdAndFindingIdIsNullOrderByCreatedAtAscIdAsc(Long taskId);

    long countByTaskId(Long taskId);

    long countByFindingId(Long findingId);
}
