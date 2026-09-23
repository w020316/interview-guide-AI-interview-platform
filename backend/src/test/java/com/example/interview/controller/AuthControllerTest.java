package com.example.interview.controller;

import com.example.interview.entity.UserEntity;
import com.example.interview.repository.UserRepository;
import com.example.interview.security.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AuthController} MockMvc 集成测试
 *
 * <p>覆盖注册/登录的入参校验、用户名重复、密码错误等核心路径。
 * 通过 @MockBean 隔离 UserRepository / PasswordEncoder / JwtUtil，不连真实数据库。
 * 排除 Spring Security 自动配置（AuthController 本就是 permitAll 端点）。
 */
@WebMvcTest(controllers = AuthController.class,
        properties = {"spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration",
                "app.auth.register-limit-per-hour=1000"})
class AuthControllerTest {

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
    private com.example.interview.service.UserBanRegistry userBanRegistry;

    @MockBean
    private com.example.interview.security.TokenBlacklistService tokenBlacklist;

    @Nested
    @DisplayName("POST /api/auth/register 注册")
    class Register {

        @Test
        @DisplayName("合法入参注册成功，返回 200 + token")
        void register_validInput_returnsToken() throws Exception {
            when(userRepository.existsByUsername("alice")).thenReturn(false);
            when(userRepository.existsByEmail(anyString())).thenReturn(false);
            when(userRepository.saveAndFlush(any(UserEntity.class))).thenAnswer(inv -> {
                UserEntity u = inv.getArgument(0);
                u.setId(1L);
                return u;
            });
            when(jwtUtil.generateToken("1", "alice")).thenReturn("mock.jwt.token");

            String body = objectMapper.writeValueAsString(Map.of(
                    "username", "alice", "password", "123456", "email", "a@b.com"));

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("mock.jwt.token"));
        }

        @Test
        @DisplayName("用户名或密码为空返回 400")
        void register_missingFields_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of("username", "alice"));

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("用户名和密码不能为空"));
        }

        @Test
        @DisplayName("用户名长度 < 2 返回 400")
        void register_shortUsername_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "username", "a", "password", "123456"));

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("用户名长度需 2-32 字符"));
        }

        @Test
        @DisplayName("用户名长度 > 32 返回 400")
        void register_longUsername_returns400() throws Exception {
            String longName = "a".repeat(33);
            String body = objectMapper.writeValueAsString(Map.of(
                    "username", longName, "password", "123456"));

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("用户名长度需 2-32 字符"));
        }

        @Test
        @DisplayName("用户名含非法字符（空格）返回 400")
        void register_invalidUsernameChars_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "username", "alice bob", "password", "123456"));

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("用户名只能包含中文、字母、数字和下划线"));
        }

        @Test
        @DisplayName("用户名支持中文（合法）")
        void register_chineseUsername_valid() throws Exception {
            when(userRepository.existsByUsername("张三")).thenReturn(false);
            when(userRepository.saveAndFlush(any(UserEntity.class))).thenAnswer(inv -> {
                UserEntity u = inv.getArgument(0);
                u.setId(2L);
                return u;
            });
            when(jwtUtil.generateToken("2", "张三")).thenReturn("mock.jwt.token");

            String body = objectMapper.writeValueAsString(Map.of(
                    "username", "张三", "password", "123456"));

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("密码长度 < 6 返回 400")
        void register_shortPassword_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "username", "alice", "password", "123"));

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("密码长度需 6-64 字符"));
        }

        @Test
        @DisplayName("邮箱格式错误返回 400")
        void register_invalidEmail_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "username", "alice", "password", "123456", "email", "not-an-email"));

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("邮箱格式不正确"));
        }

        @Test
        @DisplayName("用户名已存在返回 400")
        void register_duplicateUsername_returns400() throws Exception {
            when(userRepository.existsByUsername("alice")).thenReturn(true);

            String body = objectMapper.writeValueAsString(Map.of(
                    "username", "alice", "password", "123456"));

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("用户名已存在"));
        }

        @Test
        @DisplayName("邮箱已被注册返回 400")
        void register_duplicateEmail_returns400() throws Exception {
            when(userRepository.existsByUsername("alice")).thenReturn(false);
            when(userRepository.existsByEmail("a@b.com")).thenReturn(true);

            String body = objectMapper.writeValueAsString(Map.of(
                    "username", "alice", "password", "123456", "email", "a@b.com"));

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("邮箱已被注册"));
        }

        @Test
        @DisplayName("命中管理员保留名单的用户名返回 400（防抢注 ROLE_ADMIN）")
        void register_adminReservedUsername_returns400() throws Exception {
            when(jwtUtil.isAdminUsername("小吴同学")).thenReturn(true);

            String body = objectMapper.writeValueAsString(Map.of(
                    "username", "小吴同学", "password", "123456"));

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("用户名不可用"));
            verify(userRepository, never()).saveAndFlush(any(UserEntity.class));
        }
    }

    @Nested
    @DisplayName("POST /api/auth/login 登录")
    class Login {

        @Test
        @DisplayName("合法凭证登录成功，返回 200 + token")
        void login_validCredentials_returnsToken() throws Exception {
            UserEntity user = UserEntity.builder()
                    .id(1L)
                    .username("alice")
                    .passwordHash("hashed")
                    .build();
            when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("123456", "hashed")).thenReturn(true);
            when(jwtUtil.generateToken("1", "alice")).thenReturn("mock.jwt.token");

            String body = objectMapper.writeValueAsString(Map.of(
                    "username", "alice", "password", "123456"));

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("mock.jwt.token"));
        }

        @Test
        @DisplayName("用户名或密码为空返回 400")
        void login_missingFields_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of("username", "alice"));

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("用户名和密码不能为空"));
        }

        @Test
        @DisplayName("用户名长度超限返回 400")
        void login_longUsername_returns400() throws Exception {
            String longName = "a".repeat(65);
            String body = objectMapper.writeValueAsString(Map.of(
                    "username", longName, "password", "123456"));

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("用户名或密码长度超限"));
        }

        @Test
        @DisplayName("用户不存在返回 401")
        void login_userNotFound_returns401() throws Exception {
            when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

            String body = objectMapper.writeValueAsString(Map.of(
                    "username", "ghost", "password", "123456"));

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(401))
                    .andExpect(jsonPath("$.message").exists());
        }

        @Test
        @DisplayName("密码错误返回 401")
        void login_wrongPassword_returns401() throws Exception {
            UserEntity user = UserEntity.builder()
                    .id(1L).username("alice").passwordHash("hashed").build();
            when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

            String body = objectMapper.writeValueAsString(Map.of(
                    "username", "alice", "password", "wrong"));

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(401));
        }

        /**
         * P1-02（2026-09-23）回归：原实现只按 IP 计数，而 IP 是攻击者可轮换的资源。
         * 生产实测——对同一账号连续输错密码，返回的「还可尝试 N 次」在 4→3→4→3 之间交替
         * （每次请求落到不同边缘节点被当成不同 IP），5 次锁定形同虚设，可无限重试同一账号。
         * 本用例让每次请求都来自不同 IP，验证账号维度仍能触发锁定。
         *
         * <p>用独立用户名 lockout-probe 隔离，避免污染其它用例的计数（controller 是单例，计数是实例状态）。
         */
        @Test
        @DisplayName("同一账号跨 IP 连续失败仍会锁定（P1-02：轮换 IP 无法绕过）")
        void login_accountLockout_notBypassableByRotatingIp() throws Exception {
            String username = "lockout-probe";
            when(userRepository.findByUsername(username)).thenReturn(Optional.empty());
            String body = objectMapper.writeValueAsString(Map.of(
                    "username", username, "password", "wrong"));

            // 前 4 次：每次换一个客户端 IP，仍在计数范围内 → 401
            for (int i = 0; i < 4; i++) {
                final int n = i;
                mockMvc.perform(post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .with(req -> { req.setRemoteAddr("203.0.113." + (n + 1)); return req; })
                                .content(body))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.code").value(401));
            }

            // 第 5 次：账号维度达到阈值 → 429（若只按 IP 计数，这里会是该 IP 的第 1 次失败、返回 401）
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .with(req -> { req.setRemoteAddr("203.0.113.5"); return req; })
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(429));

            // 第 6 次：换一个全新 IP，此时走「预检已锁定」分支，且明确指出来自账号维度
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .with(req -> { req.setRemoteAddr("203.0.113.200"); return req; })
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(429))
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("该账号")));
        }
    }

    @Nested
    @DisplayName("POST /api/auth/logout 登出")
    class Logout {

        @Test
        @DisplayName("登出始终返回 200")
        void logout_returns200() throws Exception {
            mockMvc.perform(post("/api/auth/logout"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("带 Bearer token 登出：jti 被写入黑名单（P1 登出即吊销）")
        void logout_withBearerToken_revokes() throws Exception {
            when(jwtUtil.isValid("tok-1")).thenReturn(true);
            when(jwtUtil.extractJti("tok-1")).thenReturn("jti-1");
            when(jwtUtil.extractExpirationMs("tok-1")).thenReturn(System.currentTimeMillis() + 60_000L);

            mockMvc.perform(post("/api/auth/logout")
                            .header("Authorization", "Bearer tok-1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            verify(tokenBlacklist).revoke(eq("jti-1"), anyLong());
        }

        @Test
        @DisplayName("旧版无 jti 的 token 登出：幂等成功且不写黑名单")
        void logout_tokenWithoutJti_idempotent() throws Exception {
            when(jwtUtil.isValid("tok-old")).thenReturn(true);
            when(jwtUtil.extractJti("tok-old")).thenReturn(null);

            mockMvc.perform(post("/api/auth/logout")
                            .header("Authorization", "Bearer tok-old"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            verify(tokenBlacklist, never()).revoke(anyString(), anyLong());
        }
    }
}
