package com.codeguardian.service.auth;

import cn.dev33.satoken.stp.StpInterface;
import com.codeguardian.repository.RolePermissionRepository;
import com.codeguardian.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Sa-Token权限与角色解析实现
 *
 * <p>基于数据库的用户-角色与角色-权限关系，按登录ID加载当前主体的权限与角色列表。</p>
 */
@Component
@RequiredArgsConstructor
public class SaPermissionImpl implements StpInterface {

    private final UserRoleRepository userRoleRepository;
    private final RolePermissionRepository rolePermissionRepository;

    /**
     * 加载权限列表
     *
     * @param loginId 登录ID（用户ID）
     * @param loginType 登录类型（未使用）
     * @return 权限代码列表
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        Long userId = Long.parseLong(String.valueOf(loginId));
        List<com.codeguardian.entity.UserRole> urs = userRoleRepository.findByUserId(userId);
        Set<String> perms = new HashSet<>();
        for (com.codeguardian.entity.UserRole ur : urs) {
            perms.addAll(rolePermissionRepository.findPermissionCodesByRoleId(ur.getRoleId()));
        }
        return new ArrayList<>(perms);
    }

    /**
     * 加载角色列表
     *
     * @param loginId 登录ID（用户ID）
     * @param loginType 登录类型（未使用）
     * @return 角色代码列表
     */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        Long userId = Long.parseLong(String.valueOf(loginId));
        return userRoleRepository.findRoleCodesByUserId(userId);
    }
}
