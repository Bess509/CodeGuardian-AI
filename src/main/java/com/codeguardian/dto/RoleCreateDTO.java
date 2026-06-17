package com.codeguardian.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 创建角色DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleCreateDTO {
    
    @NotBlank(message = "角色代码不能为空")
    @Size(min = 2, max = 32, message = "角色代码长度必须在2-32之间")
    private String code;
    
    @NotBlank(message = "角色名称不能为空")
    @Size(min = 2, max = 64, message = "角色名称长度必须在2-64之间")
    private String name;
    
    private String description;
    
    @Builder.Default
    private Integer status = 0; // 默认激活
    
    private List<String> permissionCodes; // 权限代码列表
}

