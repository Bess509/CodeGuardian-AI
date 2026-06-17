package com.codeguardian.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户查询DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserQueryDTO {
    
    /**
     * 搜索关键词（用户名、邮箱）
     */
    private String keyword;
    
    /**
     * 状态筛选（0=激活, 1=未激活, 2=锁定）
     */
    private Integer status;
    
    /**
     * 角色代码筛选
     */
    private String roleCode;
    
    /**
     * 页码（从0开始）
     */
    @Builder.Default
    private Integer page = 0;
    
    /**
     * 每页大小
     */
    @Builder.Default
    private Integer size = 10;
}

