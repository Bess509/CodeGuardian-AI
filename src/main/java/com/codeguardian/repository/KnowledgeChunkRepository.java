package com.codeguardian.repository;

import com.codeguardian.entity.KnowledgeChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, String> {

    List<KnowledgeChunk> findByDocumentIdOrderByChunkIndexAsc(String documentId);

    long deleteByDocumentId(String documentId);
}
