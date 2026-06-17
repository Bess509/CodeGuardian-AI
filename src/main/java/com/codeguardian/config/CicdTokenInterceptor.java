package com.codeguardian.config;

import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class CicdTokenInterceptor implements HandlerInterceptor {

    private static final String TOKEN_HEADER = "X-CodeGuardian-Token";

    @Value("${app.cicd.api-token:${CODEGUARDIAN_CI_TOKEN:}}")
    private String apiToken;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (StpUtil.isLogin()) {
            return true;
        }
        String provided = request.getHeader(TOKEN_HEADER);
        if (provided == null || provided.isBlank()) {
            String authorization = request.getHeader("Authorization");
            if (authorization != null && authorization.startsWith("Bearer ")) {
                provided = authorization.substring("Bearer ".length()).trim();
            }
        }
        if (apiToken != null && !apiToken.isBlank() && apiToken.equals(provided)) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"invalid_ci_token\"}");
        return false;
    }
}
