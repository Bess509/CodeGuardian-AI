package com.codeguardian.service;

import com.codeguardian.dto.*;
import com.codeguardian.entity.Permission;
import com.codeguardian.entity.Role;
import com.codeguardian.entity.RolePermission;
import com.codeguardian.repository.PermissionRepository;
import com.codeguardian.repository.RolePermissionRepository;
import com.codeguardian.repository.RoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 角色服务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RoleService {
    
    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final PermissionRepository permissionRepository;
    
    /**
     * 查询所有角色
     */
    @Transactional(readOnly = true)
    public List<Role> getAllRoles() {
        return roleRepository.findAll();
    }
    
    /**
     * 查询所有角色（包含权限信息）
     */
    @Transactional(readOnly = true)
    public List<RoleDTO> getAllRolesWithPermissions() {
        List<Role> roles = roleRepository.findAll();
        return roles.stream().map(role -> {
            List<String> permissions = rolePermissionRepository.findPermissionCodesByRoleId(role.getId());
            return RoleDTO.builder()
                .id(role.getId())
                .code(role.getCode())
                .name(role.getName())
                .description(role.getDescription())
                .status(role.getStatus())
                .permissions(permissions)
                .build();
        }).collect(Collectors.toList());
    }
    
    /**
     * 根据代码查询角色
     */
    @Transactional(readOnly = true)
    public Role getRoleByCode(String code) {
        return roleRepository.findByCode(code)
            .orElseThrow(() -> new RuntimeException("角色不存在"));
    }
    
    /**
     * 根据ID查询角色
     */
    @Transactional(readOnly = true)
    public RoleDTO getRoleById(Long id) {
        Role role = roleRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("角色不存在"));
        List<String> permissions = rolePermissionRepository.findPermissionCodesByRoleId(role.getId());
        return RoleDTO.builder()
            .id(role.getId())
            .code(role.getCode())
            .name(role.getName())
            .description(role.getDescription())
            .status(role.getStatus())
            .permissions(permissions)
            .build();
    }
    
    /**
     * 创建角色
     */
    @Transactional
    public RoleDTO createRole(RoleCreateDTO createDTO) {
        // 检查角色代码是否已存在
        if (roleRepository.existsByCode(createDTO.getCode())) {
            throw new RuntimeException("角色代码已存在");
        }
        
        // 创建角色
        Role role = Role.builder()
            .code(createDTO.getCode())
            .name(createDTO.getName())
            .description(createDTO.getDescription())
            .status(createDTO.getStatus() != null ? createDTO.getStatus() : 0)
            .createdAt(LocalDateTime.now())
            .build();
        
        role = roleRepository.save(role);
        
        // 分配权限
        if (createDTO.getPermissionCodes() != null && !createDTO.getPermissionCodes().isEmpty()) {
            assignPermissions(role.getId(), createDTO.getPermissionCodes());
        }
        
        log.info("创建角色成功: code={}, id={}", role.getCode(), role.getId());
        return getRoleById(role.getId());
    }
    
    /**
     * 更新角色
     */
    @Transactional
    public RoleDTO updateRole(Long id, RoleUpdateDTO updateDTO) {
        Role role = roleRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("角色不存在"));
        
        // 更新基本信息
        if (StringUtils.hasText(updateDTO.getName())) {
            role.setName(updateDTO.getName());
        }
        if (updateDTO.getDescription() != null) {
            role.setDescription(updateDTO.getDescription());
        }
        if (updateDTO.getStatus() != null) {
            role.setStatus(updateDTO.getStatus());
        }
        
        role = roleRepository.save(role);
        
        // 更新权限
        if (updateDTO.getPermissionCodes() != null) {
            assignPermissions(id, updateDTO.getPermissionCodes());
        }
        
        log.info("更新角色成功: id={}", id);
        return getRoleById(id);
    }
    
    /**
     * 删除角色
     */
    @Transactional
    public void deleteRole(Long id) {
        if (!roleRepository.existsById(id)) {
            throw new RuntimeException("角色不存在");
        }
        
        // 删除角色权限关联
        rolePermissionRepository.deleteByRoleId(id);
        
        // 删除角色
        roleRepository.deleteById(id);
        
        log.info("删除角色成功: id={}", id);
    }
    
    /**
     * 分配权限
     */
    @Transactional
    public void assignPermissions(Long roleId, List<String> permissionCodes) {
        // 删除现有权限
        rolePermissionRepository.deleteByRoleId(roleId);
        
        // 添加新权限
        for (String permissionCode : permissionCodes) {
            Permission permission = permissionRepository.findByCode(permissionCode)
                .orElseThrow(() -> new RuntimeException("权限不存在: " + permissionCode));
            
            RolePermission rolePermission = RolePermission.builder()
                .roleId(roleId)
                .permissionId(permission.getId())
                .createdAt(LocalDateTime.now())
                .build();
            
            rolePermissionRepository.save(rolePermission);
        }
        
        log.info("分配权限成功: roleId={}, permissions={}", roleId, permissionCodes);
    }
}

