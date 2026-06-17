package com.codeguardian.service;

import com.codeguardian.CodeReviewApplication;
import com.codeguardian.dto.LoginRequestDTO;
import com.codeguardian.dto.LoginResponseDTO;
import com.codeguardian.entity.Role;
import com.codeguardian.entity.User;
import com.codeguardian.entity.UserRole;
import com.codeguardian.repository.RoleRepository;
import com.codeguardian.repository.UserRepository;
import com.codeguardian.repository.UserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = CodeReviewApplication.class)
@Transactional
public class AuthServiceTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private BCryptPasswordEncoder passwordEncoder;

    private User testUser;

    @BeforeEach
    void setUp() {
        User existing = userRepository.findByUsername("unituser").orElse(null);
        if (existing != null) {
            testUser = existing;
        } else {
            User u = User.builder()
                    .username("unituser")
                    .email("unituser@example.com")
                    .passwordHash(passwordEncoder.encode("pass1234"))
                    .realName("测试用户")
                    .status(0)
                    .createdAt(LocalDateTime.now())
                    .build();
            testUser = userRepository.save(u);
            Role viewer = roleRepository.findByCode("VIEWER").orElseThrow();
            UserRole ur = UserRole.builder()
                    .userId(testUser.getId())
                    .roleId(viewer.getId())
                    .createdAt(LocalDateTime.now())
                    .build();
            userRoleRepository.save(ur);
        }
    }

    @Test
    void should_login_success_when_credentials_valid() {
        LoginRequestDTO req = LoginRequestDTO.builder()
                .usernameOrEmail("unituser")
                .password("pass1234")
                .build();
        LoginResponseDTO resp = authService.login(req, "127.0.0.1");
        assertTrue(resp.getSuccess());
        assertNotNull(resp.getToken());
        assertEquals("unituser", resp.getUsername());
    }

    @Test
    void should_fail_when_password_wrong() {
        LoginRequestDTO req = LoginRequestDTO.builder()
                .usernameOrEmail("unituser")
                .password("wrongpass")
                .build();
        LoginResponseDTO resp = authService.login(req, "127.0.0.1");
        assertFalse(resp.getSuccess());
        assertNull(resp.getToken());
    }

    @Test
    void should_fail_when_user_locked() {
        testUser.setStatus(2);
        userRepository.save(testUser);
        LoginRequestDTO req = LoginRequestDTO.builder()
                .usernameOrEmail("unituser")
                .password("pass1234")
                .build();
        LoginResponseDTO resp = authService.login(req, "127.0.0.1");
        assertFalse(resp.getSuccess());
    }
}

