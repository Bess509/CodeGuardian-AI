package com.codeguardian.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通用操作响应DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OperationResponseDTO {
    
    /**
     * 是否成功
     */
    private Boolean success;
    
    /**
     * 响应消息
     */
    private String message;
    
    /**
     * 附加数据（可选）
     */
    private Object data;

    public static OperationResponseDTO success(String message) {
        return OperationResponseDTO.builder()
                .success(true)
                .message(message)
                .build();
    }
    
    public static OperationResponseDTO success(String message, Object data) {
        return OperationResponseDTO.builder()
                .success(true)
                .message(message)
                .data(data)
                .build();
    }

    public static OperationResponseDTO error(String message) {
        return OperationResponseDTO.builder()
                .success(false)
                .message(message)
                .build();
    }
}
