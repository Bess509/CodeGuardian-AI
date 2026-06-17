package com.codeguardian.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 更新用户DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserUpdateDTO {
    
    @Size(max = 64, message = "真实姓名长度不能超过64")
    private String realName;
    
    @Email(message = "邮箱格式不正确")
    private String email;
    
    @Size(max = 16, message = "手机号长度不能超过16")
    private String phone;
    
    private Integer status;
    private List<String> roleCodes; // 角色代码列表
}

