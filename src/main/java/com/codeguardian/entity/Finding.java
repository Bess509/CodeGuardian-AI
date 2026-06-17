package com.codeguardian.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 代码审查发现的问题
 */
@Entity
@Table(name = "findings")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Finding {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * 关联的审查任务ID
     */
    @Column(name = "task_id", nullable = false)
    private Long taskId;
    
    @Column(nullable = false, columnDefinition = "integer")
    private Integer severity;
    
    /**
     * 问题标题
     */
    @Column(nullable = false)
    private String title;
    
    /**
     * 问题位置（文件路径或代码位置）
     */
    @Column(nullable = false)
    private String location;
    
    /**
     * 起始行号
     */
    private Integer startLine;
    
    /**
     * 结束行号
     */
    private Integer endLine;
    
    /**
     * 问题描述
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String description;
    
    /**
     * 修复建议
     */
    @Column(columnDefinition = "TEXT")
    private String suggestion;
    
    @Column(columnDefinition = "TEXT")
    private String diff;
    
    /**
     * 问题类别代码
     */
    @Column(name = "category", length = 32)
    private String category;
    
    /**
     * 问题来源：AI, Semgrep, RuleEngine
     */
    @Column(name = "source")
    private String source;

    @Column(name = "confidence")
    private Double confidence;

    @Column(name = "grounded")
    private Boolean grounded;

    @Column(name = "evidence_count")
    private Integer evidenceCount;

    @Column(name = "evidence_hash", length = 64)
    private String evidenceHash;

    @Column(name = "grounding_summary", columnDefinition = "TEXT")
    private String groundingSummary;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (grounded == null) {
            grounded = false;
        }
        if (evidenceCount == null) {
            evidenceCount = 0;
        }
    }
}
