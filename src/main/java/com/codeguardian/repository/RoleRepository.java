package com.codeguardian.repository;

import com.codeguardian.entity.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 角色数据访问接口
 */
@Repository
public interface RoleRepository extends JpaRepository<Role, Long> {
    
    /**
     * 根据角色代码查询角色
     */
    Optional<Role> findByCode(String code);
    
    /**
     * 检查角色代码是否存在
     */
    boolean existsByCode(String code);
}

