package com.codeguardian.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/report")
@RequiredArgsConstructor
@Slf4j
public class ReportPageController {

    private final com.codeguardian.service.SystemConfigService configService;

    @GetMapping("/{taskId}")
    @SaCheckLogin
    public String reportPage(@PathVariable("taskId") Long taskId, Model model, jakarta.servlet.http.HttpSession session) {
        com.codeguardian.util.ViewModelUtils.populateUserInfo(model, session);
        model.addAttribute("taskId", taskId);
        
        com.codeguardian.model.dto.SettingsDTO settings = configService.getSettings();
        model.addAttribute("maxIssues", settings.getMaxIssues());
        
        return "report";
    }
}

