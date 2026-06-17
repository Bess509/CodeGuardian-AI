package com.codeguardian.service.rag;

import com.codeguardian.util.MapJsonConverter;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.document.Document;

import java.util.Map;

/**
 * 知识库文档
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "knowledge_documents")
public class KnowledgeDocument {
    @Id
    private String id;
    
    @Column(nullable = false)
    private String title;
    
    @Column(columnDefinition = "TEXT")
    private String content; // 问题描述或规则内容
    
    @Column(columnDefinition = "TEXT")
    private String solution; // 修复示例或建议

    @Column(name = "category", length = 32)
    private String category;
    
    @Column(columnDefinition = "TEXT")
    @Convert(converter = MapJsonConverter.class)
    private Map<String, Object> metadata;

    private String minioBucketName;
    private String minioObjectName;
    private String contentType;
    private Long fileSize;

    @Column(name = "create_time")
    private java.time.LocalDateTime createTime;

    @PrePersist
    public void prePersist() {
        if (createTime == null) {
            createTime = java.time.LocalDateTime.now();
        }
    }

    public Document toDocument() {
        return new Document(id, content, metadata != null ? metadata : Map.of());
    }
}
