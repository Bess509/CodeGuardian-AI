package com.codeguardian.repository;

import com.codeguardian.entity.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 用户角色关联数据访问接口
 */
@Repository
public interface UserRoleRepository extends JpaRepository<UserRole, Long> {
    
    /**
     * 根据用户ID查询所有角色关联
     */
    List<UserRole> findByUserId(Long userId);
    
    /**
     * 根据角色ID查询所有用户关联
     */
    List<UserRole> findByRoleId(Long roleId);
    
    /**
     * 删除用户的所有角色关联
     */
    void deleteByUserId(Long userId);
    
    /**
     * 删除角色的所有用户关联
     */
    void deleteByRoleId(Long roleId);
    
    /**
     * 检查用户是否拥有某个角色
     */
    boolean existsByUserIdAndRoleId(Long userId, Long roleId);
    
    /**
     * 根据用户ID查询角色代码列表
     */
    @Query("SELECT r.code FROM UserRole ur JOIN Role r ON ur.roleId = r.id WHERE ur.userId = :userId AND r.status = 0")
    List<String> findRoleCodesByUserId(Long userId);
    
    /**
     * 根据用户ID查询角色名称列表
     */
    @Query("SELECT r.name FROM UserRole ur JOIN Role r ON ur.roleId = r.id WHERE ur.userId = :userId AND r.status = 0 ORDER BY r.id")
    List<String> findRoleNamesByUserId(Long userId);
}

