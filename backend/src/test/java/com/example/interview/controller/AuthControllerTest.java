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
import static org.mockito.ArgumentMatchers.anyString;
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
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration")
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

    @Nested
    @DisplayName("POST /api/auth/register 注册")
    class Register {

        @Test
        @DisplayName("合法入参注册成功，返回 200 + token")
        void register_validInput_returnsToken() throws Exception {
            when(userRepository.existsByUsername("alice")).thenReturn(false);
            when(userRepository.existsByEmail(anyString())).thenReturn(false);
            when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> {
                UserEntity u = inv.getArgument(0);
                u.setId(1L);
                return u;
            });
            when(jwtUtil.generateToken("1")).thenReturn("mock.jwt.token");

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
            when(userRepository.save(any(UserEntity.class))).thenAnswer(inv -> {
                UserEntity u = inv.getArgument(0);
                u.setId(2L);
                return u;
            });
            when(jwtUtil.generateToken("2")).thenReturn("mock.jwt.token");

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
            when(jwtUtil.generateToken("1")).thenReturn("mock.jwt.token");

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
    }
}
