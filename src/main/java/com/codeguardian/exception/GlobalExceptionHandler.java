package com.codeguardian.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;

import java.util.HashMap;
import java.util.Map;

/**
 * 全局异常处理器
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    
    /**
     * 处理资源未找到异常（404）
     */
    @ExceptionHandler({NoHandlerFoundException.class})
    public ResponseEntity<Map<String, Object>> handleResourceNotFoundException(Exception e) {
        log.warn("资源未找到: {}", e.getMessage());
        Map<String, Object> response = new HashMap<>();
        response.put("error", "资源未找到");
        response.put("message", "请求的资源不存在");
        response.put("status", HttpStatus.NOT_FOUND.value());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
    }

    /**
     * 处理文件上传大小超限异常
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e) {
        log.warn("文件上传大小超限: {}", e.getMessage());
        Map<String, Object> response = new HashMap<>();
        response.put("error", "文件过大");
        response.put("message", "上传的文件超过了允许的最大限制（50MB）");
        response.put("status", HttpStatus.BAD_REQUEST.value());
        return ResponseEntity
                .badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
    }

    /**
     * 处理未登录异常
     *
     * <p>当访问受保护资源但未建立登录态时返回401。</p>
     *
     * @param e 未登录异常
     * @return 401响应，包含错误提示
     */
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<?> handleNotLoginException(NotLoginException e, jakarta.servlet.http.HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        String xhr = request.getHeader("X-Requested-With");
        String uri = request.getRequestURI();

        boolean wantsJson = (accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE))
                || (xhr != null && xhr.equalsIgnoreCase("XMLHttpRequest"))
                || (uri != null && uri.startsWith("/api"));

        if (wantsJson) {
            Map<String, Object> response = new HashMap<>();
            response.put("error", "未登录");
            response.put("message", e.getMessage());
            response.put("status", HttpStatus.UNAUTHORIZED.value());
            return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response);
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .header("Location", "/login")
                .build();
    }

    /**
     * 处理权限不足异常
     *
     * <p>当登录态缺少所需权限时返回403。</p>
     *
     * @param e 权限不足异常
     * @return 403响应，包含错误提示
     */
    @ExceptionHandler(NotPermissionException.class)
    public ResponseEntity<Map<String, Object>> handleNotPermissionException(NotPermissionException e) {
        Map<String, Object> response = new HashMap<>();
        response.put("error", "无权限");
        response.put("message", e.getMessage());
        response.put("status", HttpStatus.FORBIDDEN.value());
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
    }
    
    /**
     * 处理参数错误异常
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgumentException(IllegalArgumentException e) {
        log.error("参数错误", e);
        Map<String, Object> response = new HashMap<>();
        response.put("error", "参数错误");
        response.put("message", e.getMessage());
        response.put("status", HttpStatus.BAD_REQUEST.value());
        return ResponseEntity
                .badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
    }
    
    /**
     * 处理运行时异常
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(RuntimeException e) {
        log.error("运行时错误", e);
        Map<String, Object> response = new HashMap<>();
        response.put("error", "服务器错误");
        response.put("message", e.getMessage());
        response.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
    }
    
    /**
     * 处理参数验证异常
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(MethodArgumentNotValidException e) {
        log.error("验证错误", e);
        Map<String, String> errors = new HashMap<>();
        e.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });
        
        Map<String, Object> response = new HashMap<>();
        response.put("error", "验证失败");
        response.put("errors", errors);
        response.put("status", HttpStatus.BAD_REQUEST.value());
        return ResponseEntity
                .badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
    }
    
    /**
     * 处理通用异常
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        log.error("未知错误", e);
        Map<String, Object> response = new HashMap<>();
        response.put("error", "服务器内部错误");
        response.put("message", e.getMessage() != null ? e.getMessage() : "未知错误");
        response.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
    }
}
