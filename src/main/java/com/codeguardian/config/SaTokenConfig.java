package com.codeguardian.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token拦截器配置
 *
 * <p>统一拦截敏感路径进行登录校验：
 * /admin/**、/api/**（排除认证接口）、/review/**。
 * 静态资源与健康检查等公共端点放行。</p>
 */
@Configuration
@RequiredArgsConstructor
public class SaTokenConfig implements WebMvcConfigurer {

    private final CicdTokenInterceptor cicdTokenInterceptor;

    /**
     * 注册Sa-Token拦截器并声明路径匹配规则
     *
     * @param registry Spring MVC拦截器注册器
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handler -> {
            StpUtil.checkLogin();
        }))
        .addPathPatterns("/admin/**", "/api/**", "/review/**")
        .excludePathPatterns(
                "/login",
                "/api/auth/**",
                "/api/v1/cicd/**",
                "/logout",
                "/actuator/**",
                "/error",
                "/css/**",
                "/js/**",
                "/images/**"
        );

        registry.addInterceptor(cicdTokenInterceptor)
                .addPathPatterns("/api/v1/cicd/**");
    }
}
