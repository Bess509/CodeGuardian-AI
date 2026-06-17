package com.codeguardian.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 权限DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PermissionDTO {
    private Long id;
    private String code;
    private String name;
    private String description;
    private String resource; // 资源信息，如 "TASK, REPORT" 或 "ALL"
    private String action;   // 操作信息，如 "READ" 或 "CREATE, READ" 或 "ALL"
}

