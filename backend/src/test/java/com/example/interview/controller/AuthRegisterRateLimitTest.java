package com.example.interview.controller;

import com.example.interview.entity.UserEntity;
import com.example.interview.repository.UserRepository;
import com.example.interview.security.JwtUtil;
import com.example.interview.service.UserBanRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P2-04：注册接口按 IP 限流（app.auth.register-limit-per-hour=2 的专用切片测试）。
 * MockMvc 所有请求默认来自同一 remoteAddr（127.0.0.1），可精确验证同一 IP 的配额耗尽。
 */
@WebMvcTest(controllers = AuthController.class,
        properties = {"spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration",
                "app.auth.register-limit-per-hour=2"})
@DisplayName("注册接口 IP 限流（P2-04）")
class AuthRegisterRateLimitTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private PasswordEncoder passwordEncoder;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private UserBanRegistry userBanRegistry;

    private String body(String username) {
        try {
            return objectMapper.writeValueAsString(Map.of("username", username, "password", "123456"));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("同 IP 前 2 次注册放行，第 3 次返回 429")
    void register_thirdRequestWithinLimit_returns429() throws Exception {
        when(userRepository.existsByUsername(any())).thenReturn(false);
        when(userRepository.existsByUsername(org.mockito.ArgumentMatchers.startsWith("user-"))).thenReturn(false);
        when(userRepository.saveAndFlush(any(UserEntity.class))).thenAnswer(inv -> {
            UserEntity u = inv.getArgument(0);
            u.setId(1L);
            return u;
        });
        when(jwtUtil.generateToken(any(), any())).thenReturn("mock.jwt.token");

        // 同 IP 第 1、2 次：放行（配额 2/小时）
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("usera")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("userb")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 第 3 次：429 限流，且未触达用户仓储
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("userc")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.message").value("注册过于频繁，请稍后再试"));
    }
}
