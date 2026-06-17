package com.codeguardian.entity;

import jakarta.persistence.*;
import com.codeguardian.enums.TaskStatusEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 代码审查任务实体
 */
@Entity
@Table(name = "review_tasks")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewTask {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * 任务名称
     */
    @Column(nullable = false)
    private String name;
    
    @Column(nullable = false, columnDefinition = "integer")
    private Integer reviewType;
    
    /**
     * 审查范围（路径或代码片段）
     */
    @Column(columnDefinition = "TEXT")
    private String scope;
    
    @Column(nullable = false, columnDefinition = "integer")
    private Integer status;
    
    /**
     * 创建时间
     */
    @Column(nullable = false)
    private LocalDateTime createdAt;
    
    /**
     * 完成时间
     */
    private LocalDateTime completedAt;
    
    
    /**
     * 错误信息（如果任务失败）
     */
    @Column(columnDefinition = "TEXT")
    private String errorMessage;
    
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = TaskStatusEnum.PENDING.getValue();
        }
    }
}
