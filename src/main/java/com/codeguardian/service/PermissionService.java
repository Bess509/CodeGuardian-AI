package com.codeguardian.service;

import com.codeguardian.dto.PermissionDTO;
import com.codeguardian.entity.Permission;
import com.codeguardian.repository.PermissionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 权限服务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PermissionService {
    
    private final PermissionRepository permissionRepository;
    
    /**
     * 查询所有权限
     */
    @Transactional(readOnly = true)
    public List<Permission> getAllPermissions() {
        return permissionRepository.findAll();
    }
    
    /**
     * 查询所有权限（DTO格式）
     */
    @Transactional(readOnly = true)
    public List<PermissionDTO> getAllPermissionDTOs() {
        List<Permission> permissions = permissionRepository.findAll();
        return permissions.stream().map(permission -> {
            String resource = getResourceString(permission.getResource(), permission.getCode());
            String action = getActionString(permission.getAction(), permission.getCode());
            return PermissionDTO.builder()
                .id(permission.getId())
                .code(permission.getCode())
                .name(permission.getName())
                .description(permission.getDescription())
                .resource(resource)
                .action(action)
                .build();
        }).collect(Collectors.toList());
    }
    
    /**
     * 获取资源字符串
     */
    private String getResourceString(Integer resource, String code) {
        if (code.equals("ADMIN")) {
            return "ALL";
        } else if (code.equals("QUERY")) {
            return "TASK, REPORT";
        } else if (code.equals("REVIEW")) {
            return "TASK";
        } else if (code.equals("CONFIG")) {
            return "CONFIG";
        }
        return "";
    }
    
    /**
     * 获取操作字符串
     */
    private String getActionString(Integer action, String code) {
        if (code.equals("ADMIN")) {
            return "ALL";
        } else if (code.equals("QUERY")) {
            return "READ";
        } else if (code.equals("REVIEW")) {
            return "CREATE, READ";
        } else if (code.equals("CONFIG")) {
            return "READ, UPDATE";
        }
        return "";
    }
}

