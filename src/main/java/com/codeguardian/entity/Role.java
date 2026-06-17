package com.codeguardian.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 角色实体
 */
@Entity
@Table(name = "roles")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Role {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * 角色代码（唯一）
     */
    @Column(nullable = false, unique = true, length = 32)
    private String code;
    
    /**
     * 角色名称
     */
    @Column(nullable = false, length = 64)
    private String name;
    
    /**
     * 角色描述
     */
    @Column(columnDefinition = "TEXT")
    private String description;
    
    /**
     * 角色状态：0=ACTIVE, 1=INACTIVE
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer status = 0;
    
    /**
     * 创建时间
     */
    @Column(nullable = false)
    private LocalDateTime createdAt;
    
    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = 0; // ACTIVE
        }
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

