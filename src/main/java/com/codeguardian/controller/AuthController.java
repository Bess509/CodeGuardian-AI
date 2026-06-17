package com.codeguardian.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.codeguardian.dto.LoginRequestDTO;
import com.codeguardian.dto.LoginResponseDTO;
import com.codeguardian.dto.UserCreateDTO;
import com.codeguardian.dto.UserDTO;
import com.codeguardian.service.AuthService;
import com.codeguardian.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

/**
 * 认证控制器
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class AuthController {
    
    private final AuthService authService;
    private final UserService userService;

    private void performLogout(HttpServletRequest request, HttpServletResponse response) {
        StpUtil.logout();
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        String tokenName = cn.dev33.satoken.SaManager.getConfig().getTokenName();
        Cookie cookie = new Cookie(tokenName, "");
        cookie.setMaxAge(0);
        cookie.setPath("/");
        response.addCookie(cookie);
    }
    
    /**
     * 显示登录页面
     *
     * @param model 视图模型，注入登录表单数据
     * @return 登录页面模板名称
     */
    @GetMapping("/login")
    public String loginPage(Model model) {
        model.addAttribute("loginRequest", new LoginRequestDTO());
        return "login";
    }
    
    /**
     * 处理登录表单提交
     *
     * <p>校验参数后调用认证服务，登录成功将用户信息保存至Session，失败则返回错误信息。</p>
     *
     * @param request 登录请求数据（用户名或邮箱 + 密码）
     * @param bindingResult 参数校验结果
     * @param model 视图模型，用于返回错误提示
     * @param httpRequest 原始HTTP请求，用于解析客户端IP
     * @return 登录成功跳转到用户管理页，失败返回登录页
     */
    @PostMapping("/login")
    public String login(@Valid @ModelAttribute("loginRequest") LoginRequestDTO request,
                       BindingResult bindingResult,
                       Model model,
                       HttpServletRequest httpRequest) {
        
        if (bindingResult.hasErrors()) {
            model.addAttribute("error", "请填写完整的登录信息");
            return "login";
        }
        
        // 获取客户端IP
        String clientIp = getClientIp(httpRequest);
        
        // 执行登录
        LoginResponseDTO response = authService.login(request, clientIp);
        
        if (response.getSuccess()) {
            // 登录成功，将用户信息保存到Session
            HttpSession session = httpRequest.getSession(true); // 确保创建新Session
            session.setAttribute("userId", response.getUserId());
            session.setAttribute("username", response.getUsername());
            session.setAttribute("roleName", response.getRealName()); // realName现在存储的是角色名
            session.setAttribute("realName", response.getRealName()); // 保持兼容性
            
            // 设置Session超时时间（30分钟）
            session.setMaxInactiveInterval(30 * 60);
            
            log.info("Session保存用户信息: userId={}, username={}, roleName={}, sessionId={}", 
                response.getUserId(), response.getUsername(), response.getRealName(), session.getId());
            
            // 登录成功，重定向到仪表盘
            return "redirect:/dashboard";
        } else {
            // 登录失败，返回错误信息
            model.addAttribute("error", response.getMessage());
            return "login";
        }
    }
    
    /**
     * 登录API（AJAX）
     *
     * <p>成功返回包含`token`的响应对象；失败返回400。</p>
     *
     * @param request 登录请求数据
     * @param httpRequest 原始HTTP请求
     * @return 登录响应对象，含成功标识与消息
     */
    @PostMapping("/api/auth/login")
    @ResponseBody
    public ResponseEntity<LoginResponseDTO> loginApi(@Valid @RequestBody LoginRequestDTO request,
                                                      HttpServletRequest httpRequest) {
        String clientIp = getClientIp(httpRequest);
        LoginResponseDTO response = authService.login(request, clientIp);
        
        if (response.getSuccess()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 注册API
     *
     * <p>创建新用户并为其自动分配`VIEWER`角色；成功后自动登录并返回token。</p>
     *
     * @param createDTO 用户创建请求
     * @param httpRequest 原始HTTP请求
     * @return 登录响应对象（注册后立即登录）
     */
    @PostMapping("/api/auth/register")
    @ResponseBody
    public ResponseEntity<LoginResponseDTO> register(@Valid @RequestBody UserCreateDTO createDTO,
                                                     HttpServletRequest httpRequest) {
        if (createDTO.getRoleCodes() == null || createDTO.getRoleCodes().isEmpty()) {
            createDTO.setRoleCodes(java.util.List.of("VIEWER"));
        }
        UserDTO user = userService.createUser(createDTO);
        LoginRequestDTO loginReq = LoginRequestDTO.builder()
                .usernameOrEmail(user.getUsername())
                .password(createDTO.getPassword())
                .build();
        String clientIp = getClientIp(httpRequest);
        LoginResponseDTO resp = authService.login(loginReq, clientIp);
        return ResponseEntity.ok(resp);
    }

    /**
     * 登出API
     *
     * <p>调用Sa-Token注销当前登录态，并清理HTTP Session。</p>
     *
     * @param request 原始HTTP请求
     * @return 200表示登出成功
     */
    @PostMapping("/api/auth/logout")
    @ResponseBody
    public ResponseEntity<Void> logoutApi(HttpServletRequest request, HttpServletResponse response) {
        performLogout(request, response);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/logout")
    public String logout(HttpServletRequest request, HttpServletResponse response) {
        performLogout(request, response);
        return "redirect:/login";
    }
    
    /**
     * 解析客户端IP地址
     *
     * <p>优先读取反向代理头（X-Forwarded-For / X-Real-IP），无法获取时回退到`request.getRemoteAddr()`。</p>
     *
     * @param request 原始HTTP请求
     * @return 客户端IP（可能为代理链首个地址）
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 处理多个IP的情况，取第一个
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
