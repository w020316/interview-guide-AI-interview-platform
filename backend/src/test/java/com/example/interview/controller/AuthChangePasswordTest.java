package com.example.interview.controller;

import com.example.interview.entity.UserEntity;
import com.example.interview.interceptor.RateLimitInterceptor;
import com.example.interview.repository.UserRepository;
import com.example.interview.security.JwtUtil;
import com.example.interview.security.TokenBlacklistService;
import com.example.interview.service.UserBanRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AuthController#changePassword} 的 MockMvc 测试（G5，2026-10-18）。
 *
 * <p>独立成类而不塞进 {@code AuthControllerTest}：改密有独立的限流器
 * （{@code app.auth.change-password-limit-per-hour}），其配置值必须与其他用例
 * 不同才能既测到限流、又不污染正常路径。两个类各自持有上下文，互不影响。
 *
 * <p>关键断言取向：**只断言「不通过」是不够的**。改密失败时必须同时断言
 * 「没有落库」（{@code saveAndFlush} 未被调用），否则「先写库再报错」这种
 * 顺序错误会被漏掉。
 */
@WebMvcTest(controllers = AuthController.class,
        properties = {"spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration",
                "app.auth.register-limit-per-hour=1000",
                // 限流阈值设为 3：既能验证第 4 次被拒，又不影响其他用例（各自用不同 userId）
                "app.auth.change-password-limit-per-hour=3"})
class AuthChangePasswordTest {

    private static final String OLD_HASH = "$2a$10$oldHashOldHashOldHashOldHashOldHashOldHashOldHashOldHa";

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

    @MockBean
    private TokenBlacklistService tokenBlacklist;

    /**
     * 限流拦截器必须在切片里打成桩并显式放行。
     *
     * <p>为什么：@WebMvcTest 会装配 WebMvcConfigurer 注册的拦截器，Mockito 对
     * {@code boolean preHandle} 的默认返回值是 {@code false} → 请求被拦断，表现为
     * **HTTP 503**（不是 200，也不是 401，极易误判成服务不可用）。
     * 带 Authorization 头的用例会命中它，不带头的不会 —— 这正是本类第一条
     * 「只有 revoke 那个用例挂掉」的原因。
     */
    @MockBean
    private RateLimitInterceptor rateLimitInterceptor;

    @BeforeEach
    void allowRateLimit() throws Exception {
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** 造一个已登录用户；userId 用不同值可避开限流桶互相影响 */
    private void loginAs(String userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    private UserEntity existingUser(long id, String username) {
        return UserEntity.builder()
                .id(id)
                .username(username)
                .passwordHash(OLD_HASH)
                .build();
    }

    private String body(String oldPw, String newPw) throws Exception {
        return objectMapper.writeValueAsString(Map.of("oldPassword", oldPw, "newPassword", newPw));
    }

    @Nested
    @DisplayName("POST /api/auth/change-password 修改密码")
    class ChangePassword {

        @Test
        @DisplayName("原密码正确 → 200，落库的是新密码的哈希")
        void change_success_persistsNewHash() throws Exception {
            loginAs("1");
            UserEntity user = existingUser(1L, "alice");
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("OldPw#2026", OLD_HASH)).thenReturn(true);
            when(passwordEncoder.matches("NewPw#2026", OLD_HASH)).thenReturn(false);
            when(passwordEncoder.encode("NewPw#2026")).thenReturn("$2a$10$newHash");

            mockMvc.perform(post("/api/auth/change-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("OldPw#2026", "NewPw#2026")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
            verify(userRepository).saveAndFlush(captor.capture());
            assertThat(captor.getValue().getPasswordHash()).isEqualTo("$2a$10$newHash");
        }

        @Test
        @DisplayName("原密码错误 → 400，且不落库")
        void change_wrongOldPassword_returns400AndNoWrite() throws Exception {
            loginAs("2");
            when(userRepository.findById(2L)).thenReturn(Optional.of(existingUser(2L, "bob")));
            when(passwordEncoder.matches("WrongPw#2026", OLD_HASH)).thenReturn(false);

            mockMvc.perform(post("/api/auth/change-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("WrongPw#2026", "NewPw#2026")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("原密码不正确"));

            verify(userRepository, never()).saveAndFlush(any(UserEntity.class));
        }

        @Test
        @DisplayName("新密码过短 → 400，且不落库")
        void change_shortNewPassword_returns400() throws Exception {
            loginAs("3");
            when(userRepository.findById(3L)).thenReturn(Optional.of(existingUser(3L, "carol")));

            mockMvc.perform(post("/api/auth/change-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("OldPw#2026", "123")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("新密码长度需 6-64 字符"));

            verify(userRepository, never()).saveAndFlush(any(UserEntity.class));
        }

        @Test
        @DisplayName("新密码与原密码相同 → 400，且不落库")
        void change_sameAsOld_returns400() throws Exception {
            loginAs("4");
            when(userRepository.findById(4L)).thenReturn(Optional.of(existingUser(4L, "dave")));
            when(passwordEncoder.matches("SamePw#2026", OLD_HASH)).thenReturn(true);

            mockMvc.perform(post("/api/auth/change-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("SamePw#2026", "SamePw#2026")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("新密码不能与原密码相同"));

            verify(userRepository, never()).saveAndFlush(any(UserEntity.class));
        }

        @Test
        @DisplayName("旧 token 被吊销：改密成功后 revoke 被调用（否则改密等于没改）")
        void change_revokesCurrentToken() throws Exception {
            loginAs("5");
            UserEntity user = existingUser(5L, "erin");
            when(userRepository.findById(5L)).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("OldPw#2026", OLD_HASH)).thenReturn(true);
            when(passwordEncoder.matches("NewPw#2026", OLD_HASH)).thenReturn(false);
            when(passwordEncoder.encode("NewPw#2026")).thenReturn("$2a$10$newHash");
            when(jwtUtil.isValid("old.jwt.token")).thenReturn(true);
            // 必须桩 extractUserId：@WebMvcTest 会把 JwtAuthFilter 一并装配，
            // 它在本 token 上会用 extractUserId 的结果**覆盖** SecurityContext 里的 principal；
            // 若返回 null，控制器看到的 principal 就是 null → 走「未认证」分支（503）。
            when(jwtUtil.extractUserId("old.jwt.token")).thenReturn("5");
            when(jwtUtil.extractJti("old.jwt.token")).thenReturn("jti-abc");
            when(jwtUtil.extractExpirationMs("old.jwt.token")).thenReturn(9_999_999L);

            mockMvc.perform(post("/api/auth/change-password")
                            .header("Authorization", "Bearer old.jwt.token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("OldPw#2026", "NewPw#2026")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            verify(tokenBlacklist).revoke(eq("jti-abc"), eq(9_999_999L));
        }

        @Test
        @DisplayName("原密码或新密码为空 → 400，且不落库")
        void change_emptyFields_returns400() throws Exception {
            loginAs("7");

            mockMvc.perform(post("/api/auth/change-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("", "")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("原密码与新密码不能为空"));

            verify(userRepository, never()).findById(anyLong());
        }

        @Test
        @DisplayName("原密码超长 → 400（BCrypt 只取前 72 字节，超长会被静默截断）")
        void change_oldPasswordTooLong_returns400() throws Exception {
            loginAs("8");

            mockMvc.perform(post("/api/auth/change-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("a".repeat(129), "NewPw#2026")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("原密码长度超限"));

            verify(userRepository, never()).saveAndFlush(any(UserEntity.class));
        }

        @Test
        @DisplayName("用户不存在（token 主体失效）→ 400，且不落库")
        void change_userNotFound_returns400() throws Exception {
            loginAs("9");
            when(userRepository.findById(9L)).thenReturn(Optional.empty());

            mockMvc.perform(post("/api/auth/change-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("OldPw#2026", "NewPw#2026")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("用户不存在"));

            verify(userRepository, never()).saveAndFlush(any(UserEntity.class));
        }

        @Test
        @DisplayName("新密码是弱口令 → 400（不能借改密绕过注册时的弱口令校验）")
        void change_weakNewPassword_returns400() throws Exception {
            loginAs("10");
            when(userRepository.findById(10L)).thenReturn(Optional.of(existingUser(10L, "grace")));
            when(passwordEncoder.matches("OldPw#2026", OLD_HASH)).thenReturn(true);

            // 12345678 是 PasswordPolicy 明确拦截的弱口令；只断言 code=400 不够，
            // 必须同时确认「没有落库」，否则「先写库再校验」会被漏掉。
            mockMvc.perform(post("/api/auth/change-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("OldPw#2026", "12345678")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400));

            verify(userRepository, never()).saveAndFlush(any(UserEntity.class));
        }

        @Test
        @DisplayName("未认证访问 → HTTP 503（BusinessException 走 @ExceptionHandler，是真 5xx）")
        void change_unauthenticated_returns503() throws Exception {
            // 故意不调用 loginAs：SecurityContext 为空。
            // 注意：这里抛的是 BusinessException，由 GlobalExceptionHandler 处理，
            // 它设置的是**真实 HTTP 状态码** 503，与控制器内 `return Result.error(...)`
            // 的「HTTP 200 + 业务码」形态不同 —— 两种形态并存是本项目的既定契约，
            // 断言时必须按异常路径写，否则会误以为又出了 bug。
            mockMvc.perform(post("/api/auth/change-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("OldPw#2026", "NewPw#2026")))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value(503));

            verify(userRepository, never()).saveAndFlush(any(UserEntity.class));
        }

        @Test
        @DisplayName("匿名令牌（生产环境的真实形态）→ 503，绝不能走到业务校验")
        void change_anonymousToken_returns503() throws Exception {
            // 这一条是线上指纹复验倒逼出来的：匿名请求在 Spring Security 里会得到一个
            // AnonymousAuthenticationToken —— isAuthenticated() 恒为 true、principal 是
            // 字符串 "anonymousUser"。此前只判 principal != null，于是匿名请求一路走到
            // 长度校验，线上返回 400「新密码长度需 6-64 字符」。
            // 注意新密码故意设为 "x"（长度 1）：若该缺陷回归，本用例会拿到 400 而失败，
            // 从而复现线上症状，而不是被一个「看起来也像失败」的断言蒙混过去。
            SecurityContextHolder.getContext().setAuthentication(
                    new AnonymousAuthenticationToken("anonymous-key", "anonymousUser",
                            List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

            mockMvc.perform(post("/api/auth/change-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("OldPw#2026", "x")))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value(503));

            verify(userRepository, never()).saveAndFlush(any(UserEntity.class));
        }

        @Test
        @DisplayName("非数字 principal（历史 token）→ 503，不进入业务校验")
        void change_nonNumericPrincipal_returns503() throws Exception {
            loginAs("legacy-user");

            mockMvc.perform(post("/api/auth/change-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("OldPw#2026", "x")))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value(503));

            verify(userRepository, never()).saveAndFlush(any(UserEntity.class));
        }

        @Test
        @DisplayName("超过限流阈值 → 429（防止拿到 token 后爆破原密码）")
        void change_exceedsLimit_returns429() throws Exception {
            loginAs("6");
            when(userRepository.findById(6L)).thenReturn(Optional.of(existingUser(6L, "frank")));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

            // 阈值 3：前 3 次进入业务逻辑（原密码错误 → 400），第 4 次被限流挡下
            for (int i = 0; i < 3; i++) {
                mockMvc.perform(post("/api/auth/change-password")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("WrongPw#2026", "NewPw#2026")))
                        .andExpect(jsonPath("$.code").value(400));
            }

            mockMvc.perform(post("/api/auth/change-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("WrongPw#2026", "NewPw#2026")))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value(429));
        }
    }

    @Nested
    @DisplayName("GET /api/auth/me 当前用户信息")
    class Me {

        @Test
        @DisplayName("普通用户 → ROLE_USER，username 取自库")
        void me_normalUser_returnsRoleUserAndUsername() throws Exception {
            loginAs("1");
            when(userBanRegistry.isBanned(1L)).thenReturn(false);
            when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser(1L, "alice")));

            mockMvc.perform(get("/api/auth/me"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.id").value("1"))
                    .andExpect(jsonPath("$.data.username").value("alice"))
                    .andExpect(jsonPath("$.data.role").value("ROLE_USER"))
                    .andExpect(jsonPath("$.data.banned").value(false));
        }

        @Test
        @DisplayName("管理员 → ROLE_ADMIN（前端据此显示后台入口）")
        void me_admin_returnsRoleAdmin() throws Exception {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("2", null,
                            List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
            when(userBanRegistry.isBanned(2L)).thenReturn(false);
            when(userRepository.findById(2L)).thenReturn(Optional.of(existingUser(2L, "root")));

            mockMvc.perform(get("/api/auth/me"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.role").value("ROLE_ADMIN"));
        }

        @Test
        @DisplayName("非数字 userId（历史 token）→ 不抛异常，username 为空串、banned=false")
        void me_nonNumericUserId_degradesGracefully() throws Exception {
            loginAs("legacy-user");

            mockMvc.perform(get("/api/auth/me"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value("legacy-user"))
                    .andExpect(jsonPath("$.data.username").value(""))
                    .andExpect(jsonPath("$.data.banned").value(false));
        }

        @Test
        @DisplayName("未认证 → HTTP 503")
        void me_unauthenticated_returns503() throws Exception {
            mockMvc.perform(get("/api/auth/me"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value(503));
        }
    }
}
