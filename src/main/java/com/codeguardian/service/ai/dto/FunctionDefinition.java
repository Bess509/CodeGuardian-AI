package com.codeguardian.service.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 函数定义DTO
 *
 * <p>定义工具函数的具体信息，包括名称、描述和参数Schema</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FunctionDefinition {
    /**
     * 函数名称
     */
    private String name;

    /**
     * 函数描述
     */
    private String description;

    /**
     * 函数参数Schema (JSON Schema格式)
     */
    private Map<String, Object> parameters;
}
