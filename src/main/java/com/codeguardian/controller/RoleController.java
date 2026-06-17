package com.codeguardian.controller;

import com.codeguardian.dto.*;
import com.codeguardian.entity.Role;
import com.codeguardian.service.RoleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;

import java.util.List;

/**
 * 角色管理控制器
 */
@Controller
@RequestMapping("/admin/roles")
@RequiredArgsConstructor
@Slf4j
public class RoleController {
    
    private final RoleService roleService;
    
    /**
     * 角色管理页面
     *
     * <p>需登录。</p>
     */
    @GetMapping
    @SaCheckLogin
    public String roleManagementPage(Model model, jakarta.servlet.http.HttpSession session) {
        com.codeguardian.util.ViewModelUtils.populateUserInfo(model, session);
        return "admin/roles";
    }
    
    /**
     * 查询所有角色（API）
     *
     * <p>需`ADMIN`权限。</p>
     */
    @GetMapping("/api")
    @ResponseBody
    @SaCheckPermission("ADMIN")
    public ResponseEntity<List<Role>> getAllRoles() {
        List<Role> roles = roleService.getAllRoles();
        return ResponseEntity.ok(roles);
    }
    
    /**
     * 查询所有角色（包含权限）（API）
     *
     * <p>需`ADMIN`权限。</p>
     */
    @GetMapping("/api/with-permissions")
    @ResponseBody
    @SaCheckPermission("ADMIN")
    public ResponseEntity<List<RoleDTO>> getAllRolesWithPermissions() {
        List<RoleDTO> roles = roleService.getAllRolesWithPermissions();
        return ResponseEntity.ok(roles);
    }
    
    /**
     * 根据ID查询角色（API）
     */
    @GetMapping("/api/{id}")
    @ResponseBody
    @SaCheckPermission("ADMIN")
    public ResponseEntity<RoleDTO> getRoleById(@PathVariable("id") Long id) {
        RoleDTO role = roleService.getRoleById(id);
        return ResponseEntity.ok(role);
    }
    
    /**
     * 创建角色（API）
     */
    @PostMapping("/api")
    @ResponseBody
    @SaCheckPermission("ADMIN")
    public ResponseEntity<RoleDTO> createRole(@Valid @RequestBody RoleCreateDTO createDTO) {
        RoleDTO role = roleService.createRole(createDTO);
        return ResponseEntity.ok(role);
    }
    
    /**
     * 更新角色（API）
     */
    @PutMapping("/api/{id}")
    @ResponseBody
    public ResponseEntity<RoleDTO> updateRole(
            @PathVariable("id") Long id,
            @Valid @RequestBody RoleUpdateDTO updateDTO) {
        RoleDTO role = roleService.updateRole(id, updateDTO);
        return ResponseEntity.ok(role);
    }
    
    /**
     * 删除角色（API）
     */
    @DeleteMapping("/api/{id}")
    @ResponseBody
    public ResponseEntity<Void> deleteRole(@PathVariable("id") Long id) {
        roleService.deleteRole(id);
        return ResponseEntity.ok().build();
    }
    
    /**
     * 分配权限（API）
     */
    @PostMapping("/api/{id}/permissions")
    @ResponseBody
    public ResponseEntity<Void> assignPermissions(
            @PathVariable("id") Long id,
            @RequestBody List<String> permissionCodes) {
        roleService.assignPermissions(id, permissionCodes);
        return ResponseEntity.ok().build();
    }
}
