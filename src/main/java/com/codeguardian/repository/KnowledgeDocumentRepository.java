package com.codeguardian.repository;

import com.codeguardian.service.rag.KnowledgeDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, String> {
    
    @Query("SELECT d FROM KnowledgeDocument d ORDER BY d.createTime DESC NULLS LAST")
    Page<KnowledgeDocument> findAllNullsLast(Pageable pageable);

    @Query("SELECT d FROM KnowledgeDocument d WHERE LOWER(d.title) LIKE LOWER(CONCAT('%', :title, '%')) ORDER BY d.createTime DESC NULLS LAST")
    Page<KnowledgeDocument> findByTitleContainingIgnoreCaseNullsLast(@Param("title") String title, Pageable pageable);
    
    Page<KnowledgeDocument> findByTitleContainingIgnoreCase(String title, Pageable pageable);
}
