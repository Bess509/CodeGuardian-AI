package com.codeguardian.service.ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 工具定义DTO
 *
 * <p>定义可以被AI模型调用的工具</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolDefinition {
    /**
     * 工具类型，目前仅支持 "function"
     */
    @Builder.Default
    private String type = "function";

    /**
     * 函数定义
     */
    private FunctionDefinition function;
}
