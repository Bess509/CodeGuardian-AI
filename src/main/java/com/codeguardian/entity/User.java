package com.codeguardian.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnTransformer;

import java.time.LocalDateTime;

/**
 * 用户实体
 */
@Entity
@Table(name = "users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    /**
     * 用户名
     */
    @Column(nullable = false, unique = true, length = 32)
    private String username;
    
    /**
     * 邮箱
     */
    @Column(nullable = false, unique = true, length = 255)
    private String email;
    
    /**
     * 密码哈希值（BCrypt）
     */
    @Column(nullable = false, length = 60)
    private String passwordHash;
    
    /**
     * 真实姓名
     */
    @Column(length = 64)
    private String realName;
    
    /**
     * 手机号
     */
    @Column(length = 16)
    private String phone;
    
    /**
     * 头像URL
     */
    @Column(columnDefinition = "TEXT")
    private String avatarUrl;
    
    /**
     * 用户状态：0=ACTIVE, 1=INACTIVE, 2=LOCKED
     */
    @Column(nullable = false)
    private Integer status;
    
    /**
     * 最后登录时间
     */
    private LocalDateTime lastLoginAt;
    
    /**
     * 最后登录IP（IPv4/IPv6字符串）
     */
    @Column(length = 45)
    private String lastLoginIp;
    
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
