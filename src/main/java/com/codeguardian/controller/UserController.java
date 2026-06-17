package com.codeguardian.controller;

import com.codeguardian.dto.*;
import com.codeguardian.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;

/**
 * 用户管理控制器
 */
@Controller
@RequestMapping("/admin/users")
@RequiredArgsConstructor
@Slf4j
public class UserController {
    
    private final UserService userService;
    
    /**
     * 用户管理页面
     *
     * <p>需登录（@SaCheckLogin），用于展示管理后台用户列表页面。</p>
     *
     * @param model 视图模型
     * @param session 当前会话，用于读取显示用户信息
     * @return 模板名称
     */
    @GetMapping
    @SaCheckLogin
    public String userManagementPage(Model model, jakarta.servlet.http.HttpSession session) {
        com.codeguardian.util.ViewModelUtils.populateUserInfo(model, session);
        return "admin/users";
    }
    
    /**
     * 分页查询用户（API）
     *
     * <p>需`ADMIN`权限。</p>
     *
     * @param keyword 关键词
     * @param status 状态筛选
     * @param roleCode 角色筛选
     * @param page 页码
     * @param size 页大小
     * @return 分页结果
     */
    @GetMapping("/api")
    @ResponseBody
    @SaCheckPermission("ADMIN")
    public ResponseEntity<PageResult<UserDTO>> queryUsers(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) Integer status,
            @RequestParam(value = "roleCode", required = false) String roleCode,
            @RequestParam(value = "page", defaultValue = "0") Integer page,
            @RequestParam(value = "size", defaultValue = "10") Integer size) {
        
        UserQueryDTO queryDTO = UserQueryDTO.builder()
            .keyword(keyword)
            .status(status)
            .roleCode(roleCode)
            .page(page)
            .size(size)
            .build();
        
        PageResult<UserDTO> result = userService.queryUsers(queryDTO);
        return ResponseEntity.ok(result);
    }
    
    /**
     * 根据ID查询用户（API）
     *
     * <p>需`ADMIN`权限。</p>
     */
    @GetMapping("/api/{id}")
    @ResponseBody
    @SaCheckPermission("ADMIN")
    public ResponseEntity<UserDTO> getUserById(@PathVariable("id") Long id) {
        UserDTO user = userService.getUserById(id);
        return ResponseEntity.ok(user);
    }
    
    /**
     * 创建用户（API）
     *
     * <p>需`ADMIN`权限。</p>
     */
    @PostMapping("/api")
    @ResponseBody
    @SaCheckPermission("ADMIN")
    public ResponseEntity<UserDTO> createUser(@Valid @RequestBody UserCreateDTO createDTO) {
        UserDTO user = userService.createUser(createDTO);
        return ResponseEntity.ok(user);
    }
    
    /**
     * 更新用户（API）
     *
     * <p>需`ADMIN`权限。</p>
     */
    @PutMapping("/api/{id}")
    @ResponseBody
    @SaCheckPermission("ADMIN")
    public ResponseEntity<UserDTO> updateUser(
            @PathVariable("id") Long id,
            @Valid @RequestBody UserUpdateDTO updateDTO) {
        UserDTO user = userService.updateUser(id, updateDTO);
        return ResponseEntity.ok(user);
    }
    
    /**
     * 删除用户（API）
     *
     * <p>需`ADMIN`权限。</p>
     */
    @DeleteMapping("/api/{id}")
    @ResponseBody
    @SaCheckPermission("ADMIN")
    public ResponseEntity<Void> deleteUser(@PathVariable("id") Long id) {
        userService.deleteUser(id);
        return ResponseEntity.ok().build();
    }
}
