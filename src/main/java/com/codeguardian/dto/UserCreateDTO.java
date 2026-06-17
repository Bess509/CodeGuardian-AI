package com.codeguardian.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 创建用户DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserCreateDTO {
    
    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 32, message = "用户名长度必须在3-32之间")
    private String username;
    
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;
    
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度必须在6-32之间")
    private String password;
    
    private String realName;
    private String phone;
    @Builder.Default
    private Integer status = 0; // 默认激活
    private List<String> roleCodes; // 角色代码列表
}

