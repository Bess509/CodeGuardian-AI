package com.codeguardian.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 角色DTO，包含权限信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoleDTO {
    private Long id;
    private String code;
    private String name;
    private String description;
    private Integer status;
    private List<String> permissions; // 权限代码列表
}

