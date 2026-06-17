package com.codeguardian.controller;

import com.codeguardian.dto.ApiIndexDTO;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

/**
 * 根路径控制器
 */
@Controller
public class IndexController {
    
    /**
     * 处理根路径访问（重定向到仪表盘，如果未登录会由拦截器处理）
     */
    @GetMapping("/")
    public String index() {
        return "redirect:/dashboard";
    }
    
    /**
     * API根路径（返回JSON）
     */
    @GetMapping("/api")
    @ResponseBody
    public ResponseEntity<ApiIndexDTO> apiIndex() {
        ApiIndexDTO dto = ApiIndexDTO.builder()
                .name("CodeGuardian AI")
                .version("1.0.0")
                .description("专业的代码审查AI Agent")
                .endpoints(Map.of(
                        "health", "/actuator/health",
                        "api", "/api/review",
                        "login", "/api/auth/login"
                ))
                .build();
        return ResponseEntity.ok(dto);
    }
}
