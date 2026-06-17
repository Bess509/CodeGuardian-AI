package com.codeguardian.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 管理后台控制器
 */
@Controller
@RequestMapping("/admin")
public class AdminController {
    
    /**
     * 管理后台首页，重定向到用户管理
     */
    @GetMapping
    public String adminIndex() {
        return "redirect:/admin/users";
    }
}

