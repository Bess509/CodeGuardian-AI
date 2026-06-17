package com.codeguardian.util;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpSession;
import org.springframework.ui.Model;

/**
 * 视图模型工具
 */
public final class ViewModelUtils {

    private ViewModelUtils() {}

    /**
     * 将会话中的用户信息填充到视图模型
     *
     * @param model 视图模型
     * @param session 当前会话
     */
    public static void populateUserInfo(Model model, HttpSession session) {
        String username = (String) session.getAttribute("username");
        String roleName = (String) session.getAttribute("roleName");

        if (username == null || username.isEmpty()) {
            username = "ADMIN";
        }
        if (roleName == null || roleName.isEmpty()) {
            roleName = "系统管理员";
        }

        String avatarChar = username != null && !username.isEmpty()
                ? username.substring(0, 1).toUpperCase()
                : "系";

        model.addAttribute("username", username);
        model.addAttribute("roleName", roleName);
        model.addAttribute("avatarChar", avatarChar);

        boolean isAdmin = true;
        boolean canConfig = true;
        boolean canReview = true;
        boolean canQuery = true;
        try {
            isAdmin = StpUtil.hasPermission("ADMIN") || StpUtil.hasRole("ADMIN");
            canConfig = StpUtil.hasPermission("CONFIG") || isAdmin;
            canReview = StpUtil.hasPermission("REVIEW") || isAdmin;
            canQuery = StpUtil.hasPermission("QUERY") || isAdmin;
        } catch (Exception e) {
            isAdmin = "ADMIN".equalsIgnoreCase(roleName) || "系统管理员".equals(roleName);
            canConfig = isAdmin;
            canReview = isAdmin;
            canQuery = true;
        }

        model.addAttribute("canAdmin", isAdmin);
        model.addAttribute("canConfig", canConfig);
        model.addAttribute("canReview", canReview);
        model.addAttribute("canQuery", canQuery);
    }
}
