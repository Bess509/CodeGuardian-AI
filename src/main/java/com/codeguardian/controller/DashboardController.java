package com.codeguardian.controller;

import com.codeguardian.util.ViewModelUtils;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 仪表盘控制器
 */
@Controller
@lombok.RequiredArgsConstructor
public class DashboardController {

    private final com.codeguardian.service.SystemConfigService configService;
    private final com.codeguardian.service.DashboardService dashboardService;
    
    /**
     * 显示仪表盘首页
     */
    @GetMapping("/dashboard")
    public String dashboard(Model model, HttpSession session) {
        com.codeguardian.util.ViewModelUtils.populateUserInfo(model, session);
        model.addAttribute("config", configService.getSettings());
        model.addAttribute("dashboardData", dashboardService.getCachedDashboardData());
        
        return "dashboard";
    }
}
