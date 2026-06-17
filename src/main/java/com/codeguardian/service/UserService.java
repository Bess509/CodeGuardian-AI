package com.codeguardian.service;

import com.codeguardian.dto.*;
import com.codeguardian.entity.Role;
import com.codeguardian.entity.User;
import com.codeguardian.entity.UserRole;
import com.codeguardian.repository.RoleRepository;
import com.codeguardian.repository.UserRepository;
import com.codeguardian.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import jakarta.persistence.criteria.Predicate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户服务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {
    
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    
    /**
     * 分页查询用户
     */
    @Transactional(readOnly = true)
    public PageResult<UserDTO> queryUsers(UserQueryDTO queryDTO) {
        // 构建查询条件
        Specification<User> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            
            // 关键词搜索（用户名或邮箱）
            if (StringUtils.hasText(queryDTO.getKeyword())) {
                String keyword = "%" + queryDTO.getKeyword() + "%";
                Predicate usernamePredicate = cb.like(root.get("username"), keyword);
                Predicate emailPredicate = cb.like(root.get("email"), keyword);
                predicates.add(cb.or(usernamePredicate, emailPredicate));
            }
            
            // 状态筛选
            if (queryDTO.getStatus() != null) {
                predicates.add(cb.equal(root.get("status"), queryDTO.getStatus()));
            }
            
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        
        // 分页和排序
        Pageable pageable = PageRequest.of(
            queryDTO.getPage(),
            queryDTO.getSize(),
            Sort.by(Sort.Direction.DESC, "createdAt")
        );
        
        Page<User> userPage = userRepository.findAll(spec, pageable);
        
        // 如果有角色筛选，需要进一步过滤
        List<UserDTO> userDTOs = userPage.getContent().stream()
            .map(this::convertToDTO)
            .collect(Collectors.toList());
        
        // 角色筛选
        if (StringUtils.hasText(queryDTO.getRoleCode())) {
            userDTOs = userDTOs.stream()
                .filter(user -> user.getRoles().contains(queryDTO.getRoleCode()))
                .collect(Collectors.toList());
        }
        
        // 构建分页结果
        return PageResult.<UserDTO>builder()
            .content(userDTOs)
            .totalElements(userPage.getTotalElements())
            .totalPages(userPage.getTotalPages())
            .page(queryDTO.getPage())
            .size(queryDTO.getSize())
            .hasPrevious(userPage.hasPrevious())
            .hasNext(userPage.hasNext())
            .build();
    }
    
    /**
     * 根据ID查询用户
     */
    @Transactional(readOnly = true)
    public UserDTO getUserById(Long id) {
        User user = userRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("用户不存在"));
        return convertToDTO(user);
    }
    
    /**
     * 创建用户
     */
    @Transactional
    public UserDTO createUser(UserCreateDTO createDTO) {
        // 检查用户名是否已存在
        if (userRepository.existsByUsername(createDTO.getUsername())) {
            throw new RuntimeException("用户名已存在");
        }
        
        // 检查邮箱是否已存在
        if (userRepository.existsByEmail(createDTO.getEmail())) {
            throw new RuntimeException("邮箱已存在");
        }
        
        // 创建用户
        User user = User.builder()
            .username(createDTO.getUsername())
            .email(createDTO.getEmail())
            .passwordHash(passwordEncoder.encode(createDTO.getPassword()))
            .realName(createDTO.getRealName())
            .phone(createDTO.getPhone())
            .status(createDTO.getStatus() != null ? createDTO.getStatus() : 0)
            .createdAt(LocalDateTime.now())
            .build();
        
        user = userRepository.save(user);
        
        // 分配角色
        if (createDTO.getRoleCodes() != null && !createDTO.getRoleCodes().isEmpty()) {
            assignRoles(user.getId(), createDTO.getRoleCodes());
        }
        
        log.info("创建用户成功: username={}, id={}", user.getUsername(), user.getId());
        return convertToDTO(user);
    }
    
    /**
     * 更新用户
     */
    @Transactional
    public UserDTO updateUser(Long id, UserUpdateDTO updateDTO) {
        User user = userRepository.findById(id)
            .orElseThrow(() -> new RuntimeException("用户不存在"));
        
        // 更新基本信息
        if (StringUtils.hasText(updateDTO.getRealName())) {
            user.setRealName(updateDTO.getRealName());
        }
        if (StringUtils.hasText(updateDTO.getEmail())) {
            if (userRepository.existsByEmail(updateDTO.getEmail()) && 
                !user.getEmail().equals(updateDTO.getEmail())) {
                throw new RuntimeException("邮箱已被使用");
            }
            user.setEmail(updateDTO.getEmail());
        }
        if (StringUtils.hasText(updateDTO.getPhone())) {
            user.setPhone(updateDTO.getPhone());
        }
        if (updateDTO.getStatus() != null) {
            user.setStatus(updateDTO.getStatus());
        }
        
        user = userRepository.save(user);
        
        // 更新角色
        if (updateDTO.getRoleCodes() != null) {
            assignRoles(id, updateDTO.getRoleCodes());
        }
        
        log.info("更新用户成功: id={}", id);
        return convertToDTO(user);
    }
    
    /**
     * 删除用户
     */
    @Transactional
    public void deleteUser(Long id) {
        if (!userRepository.existsById(id)) {
            throw new RuntimeException("用户不存在");
        }
        
        // 删除用户角色关联
        userRoleRepository.deleteByUserId(id);
        
        // 删除用户
        userRepository.deleteById(id);
        
        log.info("删除用户成功: id={}", id);
    }
    
    /**
     * 分配角色
     */
    @Transactional
    public void assignRoles(Long userId, List<String> roleCodes) {
        // 删除现有角色
        userRoleRepository.deleteByUserId(userId);
        
        // 添加新角色
        for (String roleCode : roleCodes) {
            Role role = roleRepository.findByCode(roleCode)
                .orElseThrow(() -> new RuntimeException("角色不存在: " + roleCode));
            
            UserRole userRole = UserRole.builder()
                .userId(userId)
                .roleId(role.getId())
                .createdAt(LocalDateTime.now())
                .build();
            
            userRoleRepository.save(userRole);
        }
    }
    
    /**
     * 转换为DTO
     */
    private UserDTO convertToDTO(User user) {
        // 查询用户角色
        List<String> roleCodes = userRoleRepository.findRoleCodesByUserId(user.getId());
        
        return UserDTO.builder()
            .id(user.getId())
            .username(user.getUsername())
            .email(user.getEmail())
            .realName(user.getRealName())
            .phone(user.getPhone())
            .avatarUrl(user.getAvatarUrl())
            .status(user.getStatus())
            .roles(roleCodes)
            .createdAt(user.getCreatedAt())
            .lastLoginAt(user.getLastLoginAt())
            .lastLoginIp(user.getLastLoginIp())
            .build();
    }
}

