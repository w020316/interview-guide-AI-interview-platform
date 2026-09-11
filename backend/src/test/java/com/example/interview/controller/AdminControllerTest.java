package com.example.interview.controller;

import com.example.interview.config.SecurityConfig;
import com.example.interview.security.JwtAuthFilter;
import com.example.interview.security.JwtUtil;
import com.example.interview.service.AdminService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理后台权限测试（v1.31.4）
 * <p>@Import(SecurityConfig) 加载完整 Security 链（含 @EnableMethodSecurity），验证：
 * - 普通用户访问 /api/admin/** → 403
 * - 管理员（ROLE_ADMIN）→ 200
 */
@WebMvcTest(controllers = AdminController.class,
        properties = "app.cors.allowed-origins=http://localhost:5173")
@Import({SecurityConfig.class, JwtAuthFilter.class})
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminService adminService;

    @MockBean
    private JwtUtil jwtUtil;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("1", null, List.of(new SimpleGrantedAuthority(role))));
    }

    @Test
    @DisplayName("普通用户访问管理接口被拒 403")
    void user_forbidden() throws Exception {
        loginAs("ROLE_USER");
        mockMvc.perform(get("/api/admin/overview"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("管理员可访问总览 200")
    void admin_allowed() throws Exception {
        loginAs("ROLE_ADMIN");
        mockMvc.perform(get("/api/admin/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}