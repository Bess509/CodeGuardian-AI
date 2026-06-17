package com.codeguardian.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 更新角色DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleUpdateDTO {
    
    @Size(min = 2, max = 64, message = "角色名称长度必须在2-64之间")
    private String name;
    
    private String description;
    
    private Integer status;
    
    private List<String> permissionCodes; // 权限代码列表
}

