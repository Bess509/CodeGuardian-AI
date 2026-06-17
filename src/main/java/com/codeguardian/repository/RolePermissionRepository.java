package com.codeguardian.repository;

import com.codeguardian.entity.RolePermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 角色权限关联数据访问接口
 */
@Repository
public interface RolePermissionRepository extends JpaRepository<RolePermission, Long> {
    
    /**
     * 根据角色ID查询所有权限关联
     */
    List<RolePermission> findByRoleId(Long roleId);
    
    /**
     * 根据权限ID查询所有角色关联
     */
    List<RolePermission> findByPermissionId(Long permissionId);
    
    /**
     * 删除角色的所有权限关联
     */
    void deleteByRoleId(Long roleId);
    
    /**
     * 删除权限的所有角色关联
     */
    void deleteByPermissionId(Long permissionId);
    
    /**
     * 检查角色是否拥有某个权限
     */
    boolean existsByRoleIdAndPermissionId(Long roleId, Long permissionId);
    
    /**
     * 根据角色ID查询权限代码列表
     */
    @Query("SELECT p.code FROM RolePermission rp JOIN Permission p ON rp.permissionId = p.id WHERE rp.roleId = :roleId")
    List<String> findPermissionCodesByRoleId(Long roleId);
}

