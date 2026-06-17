package com.codeguardian.repository;

import com.codeguardian.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 用户数据访问接口
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    
    /**
     * 根据用户名查询用户
     */
    Optional<User> findByUsername(String username);
    
    /**
     * 根据邮箱查询用户
     */
    Optional<User> findByEmail(String email);
    
    /**
     * 根据用户名或邮箱查询用户
     */
    Optional<User> findByUsernameOrEmail(String username, String email);
    
    /**
     * 检查用户名是否存在
     */
    boolean existsByUsername(String username);
    
    /**
     * 检查邮箱是否存在
     */
    boolean existsByEmail(String email);
}

