package com.codeguardian.service;

import com.codeguardian.dto.LoginRequestDTO;
import com.codeguardian.dto.LoginResponseDTO;
import com.codeguardian.entity.User;
import com.codeguardian.repository.UserRepository;
import com.codeguardian.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import cn.dev33.satoken.stp.StpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 认证服务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {
    
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    
    /**
     * 用户登录
     *
     * <p>根据用户名或邮箱查询用户并校验密码与状态，通过后使用Sa-Token创建登录态，返回包含`token`的响应对象。</p>
     *
     * @param request 登录请求（用户名或邮箱、密码）
     * @param clientIp 客户端IP，用于记录审计信息
     * @return 登录响应对象，成功时包含用户基本信息与token
     */
    public LoginResponseDTO login(LoginRequestDTO request, String clientIp) {
        log.info("用户登录尝试: {}", request.getUsernameOrEmail());
        
        // 根据用户名或邮箱查询用户
        User user = userRepository.findByUsernameOrEmail(
            request.getUsernameOrEmail(), 
            request.getUsernameOrEmail()
        ).orElse(null);
        
        if (user == null) {
            log.warn("用户不存在: {}", request.getUsernameOrEmail());
            return LoginResponseDTO.builder()
                .success(false)
                .message("用户名或密码错误")
                .build();
        }
        
        // 检查用户状态
        if (user.getStatus() != 0) { // 0 = ACTIVE， 1 = INACTIVE， 2 = LOCKED
            log.warn("用户状态异常: {}, status={}", request.getUsernameOrEmail(), user.getStatus());
            return LoginResponseDTO.builder()
                .success(false)
                .message("用户已被禁用或锁定")
                .build();
        }
        
        // 验证密码
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            log.warn("密码错误: {}", request.getUsernameOrEmail());
            return LoginResponseDTO.builder()
                .success(false)
                .message("用户名或密码错误")
                .build();
        }
        
        // 更新最后登录信息
        user.setLastLoginAt(LocalDateTime.now());
        user.setLastLoginIp(clientIp);
        userRepository.save(user);
        
        // 查询用户角色名称（取第一个角色）
        List<String> roleNames = userRoleRepository.findRoleNamesByUserId(user.getId());
        String roleName = roleNames != null && !roleNames.isEmpty() 
            ? roleNames.get(0) 
            : "系统管理员"; // 默认角色名
        
        log.info("用户登录成功: {}, 角色: {}", user.getUsername(), roleName);
        
        // 使用Sa-Token建立登录态
        StpUtil.login(user.getId());
        String token = StpUtil.getTokenValue();
        return LoginResponseDTO.builder()
            .success(true)
            .message("登录成功")
            .userId(user.getId())
            .username(user.getUsername())
            .realName(roleName)
            .token(token)
            .build();
    }
}
