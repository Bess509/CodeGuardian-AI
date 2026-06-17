package com.codeguardian.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 权限实体
 */
@Entity
@Table(name = "permissions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Permission {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * 权限代码（唯一）
     */
    @Column(nullable = false, unique = true, length = 32)
    private String code;
    
    /**
     * 权限名称
     */
    @Column(nullable = false, length = 64)
    private String name;
    
    /**
     * 权限描述
     */
    @Column(columnDefinition = "TEXT")
    private String description;
    
    /**
     * 资源类型
     */
    @Column
    private Integer resource;
    
    /**
     * 操作类型
     */
    @Column
    private Integer action;
    
    /**
     * 创建时间
     */
    @Column(nullable = false)
    private LocalDateTime createdAt;
    
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}

