package com.example.interview.config;

import com.example.interview.common.Result;
import com.example.interview.controller.HealthController;
import com.example.interview.interceptor.RateLimitInterceptor;
import com.example.interview.security.JwtAuthFilter;
import com.example.interview.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link SecurityConfig} 鉴权策略集成测试
 *
 * <p>验证 Spring Security 过滤器链的 URL 鉴权规则：
 * <ul>
 *   <li>/api/info 公开访问（permitAll）</li>
 *   <li>/actuator/health、/actuator/info 公开（permitAll）</li>
 *   <li>/actuator/metrics 及其他 /actuator/** 需认证</li>
 *   <li>其余 /api/** 需认证，未认证返回 401 + JSON（自定义 authenticationEntryPoint）</li>
 *   <li>JwtAuthFilter 正确解析 Bearer token 并写入 SecurityContext</li>
 * </ul>
 *
 * <p>通过 @Import 加载 SecurityConfig + JwtAuthFilter（@WebMvcTest 默认不扫描 @Configuration），
 * @MockBean JwtUtil 控制 token 验证结果，TestSecureController 提供受保护端点载体。
 */
@WebMvcTest(controllers = {HealthController.class, SecurityConfigTest.TestSecureController.class},
        properties = "app.cors.allowed-origins=http://localhost:5173")
@Import({SecurityConfig.class, JwtAuthFilter.class})
@DisplayName("SecurityConfig 鉴权策略集成测试")
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private RateLimitInterceptor rateLimitInterceptor;

    /** HealthController 构造所需（v1.32.0 深度体检依赖） */
    @MockBean
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private RedisTemplate<String, Object> redisTemplate;

    /** v1.34.1：深度体检新增 RAG 区块，构造依赖这两个 Bean */
    @MockBean
    private com.example.interview.service.RagSearchService ragSearchService;

    @MockBean
    private com.example.interview.service.RagHealthTracker ragHealthTracker;

    /** 测试专用受保护端点（不在 permitAll 列表，需认证） */
    @RestController
    @RequestMapping("/api/test-secure")
    static class TestSecureController {
        @GetMapping
        public Result<String> secure() {
            return Result.success("ok");
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        // WebMvcConfig 注册 RateLimitInterceptor 到 /api/**，mock 放行避免干扰鉴权测试
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);
    }

    @Nested
    @DisplayName("permitAll 公开端点")
    class PublicEndpoints {

        @Test
        @DisplayName("未认证 GET /api/info 返回 200")
        void info_withoutToken_returns200() throws Exception {
            mockMvc.perform(get("/api/info"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("未认证 GET /actuator/health 不返回 401（permitAll 放行）")
        void actuatorHealth_withoutToken_not401() throws Exception {
            // actuator endpoint 在 @WebMvcTest 下未注册，但 permitAll 使请求通过 Security 过滤器链
            // 关键断言：不返回 401（说明未被 Security 拦截）
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(result -> {
                        if (result.getResponse().getStatus() == 401) {
                            throw new AssertionError("/actuator/health 应 permitAll，不应返回 401");
                        }
                    });
        }
    }

    @Nested
    @DisplayName("authenticated 受保护端点")
    class ProtectedEndpoints {

        @Test
        @DisplayName("未认证访问受保护 API 返回 401 + JSON")
        void protectedApi_withoutToken_returns401Json() throws Exception {
            mockMvc.perform(get("/api/test-secure"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401))
                    .andExpect(jsonPath("$.message").value("登录已过期或未登录，请重新登录"));
        }

        @Test
        @DisplayName("未认证 GET /actuator/metrics 返回 401（需认证）")
        void actuatorMetrics_withoutToken_returns401() throws Exception {
            mockMvc.perform(get("/actuator/metrics"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("未认证 GET /actuator/info 不返回 401（permitAll 放行）")
        void actuatorInfo_withoutToken_not401() throws Exception {
            mockMvc.perform(get("/actuator/info"))
                    .andExpect(result -> {
                        if (result.getResponse().getStatus() == 401) {
                            throw new AssertionError("/actuator/info 应 permitAll，不应返回 401");
                        }
                    });
        }

        @Test
        @DisplayName("P2-07：未认证 GET /api/auth/me 返回 401（不再落入 /api/auth/** permitAll）")
        void authMe_withoutToken_returns401() throws Exception {
            mockMvc.perform(get("/api/auth/me"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
        }

        @Test
        @DisplayName("P2-08：未认证 GET /api/health/detail 返回 401（深度体检需认证）")
        void healthDetail_withoutToken_returns401() throws Exception {
            mockMvc.perform(get("/api/health/detail"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("P2-08：未认证 GET /api/health 仍轻量放行")
        void health_withoutToken_permitAll() throws Exception {
            mockMvc.perform(get("/api/health"))
                    .andExpect(result -> {
                        if (result.getResponse().getStatus() == 401) {
                            throw new AssertionError("/api/health 轻量探活应 permitAll，不应返回 401");
                        }
                    });
        }
    }

    @Nested
    @DisplayName("JWT 认证流程")
    class JwtAuthFlow {

        @Test
        @DisplayName("带有效 token 访问受保护 API 返回 200")
        void protectedApi_withValidToken_returns200() throws Exception {
            when(jwtUtil.isValid(anyString())).thenReturn(true);
            when(jwtUtil.extractUserId(anyString())).thenReturn("1");
            when(jwtUtil.extractRole(anyString())).thenReturn("ROLE_USER");

            mockMvc.perform(get("/api/test-secure")
                            .header("Authorization", "Bearer valid-token"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("ok"));
        }

        @Test
        @DisplayName("带无效 token 访问受保护 API 返回 401")
        void protectedApi_withInvalidToken_returns401() throws Exception {
            when(jwtUtil.isValid(anyString())).thenReturn(false);

            mockMvc.perform(get("/api/test-secure")
                            .header("Authorization", "Bearer invalid-token"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401));
        }

        @Test
        @DisplayName("无 Authorization 头访问受保护 API 返回 401")
        void protectedApi_noAuthHeader_returns401() throws Exception {
            mockMvc.perform(get("/api/test-secure"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("CORS 预检（2026-09-19 真机回归防线）")
    class CorsPreflight {

        /**
         * 事故背景：前端冷启动唤醒器探测时带了 `Cache-Control` 请求头。
         * 该头不属于 CORS 安全列表头，跨域下浏览器会先发 OPTIONS 预检；
         * 若白名单未包含它，预检返回 403 且不带 CORS 响应头，浏览器随即拦截真实请求，
         * 表现为「探测永远失败、唤醒器空转、登录白等满预算」。
         * 该问题只在真实浏览器的跨域场景暴露——curl 直发不执行预检，故单测必须显式覆盖。
         */
        @Test
        @DisplayName("预检 Access-Control-Request-Headers: cache-control 必须放行")
        void preflight_withCacheControl_isAllowed() throws Exception {
            mockMvc.perform(options("/api/info")
                            .header("Origin", "http://localhost:5173")
                            .header("Access-Control-Request-Method", "GET")
                            .header("Access-Control-Request-Headers", "cache-control"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                    .andExpect(header().string("Access-Control-Allow-Headers",
                            org.hamcrest.Matchers.containsStringIgnoringCase("cache-control")));
        }

        @Test
        @DisplayName("预检自定义头（Authorization）仍放行，白名单未被收窄")
        void preflight_withAuthorization_isAllowed() throws Exception {
            mockMvc.perform(options("/api/info")
                            .header("Origin", "http://localhost:5173")
                            .header("Access-Control-Request-Method", "GET")
                            .header("Access-Control-Request-Headers", "authorization"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        }
    }
}
