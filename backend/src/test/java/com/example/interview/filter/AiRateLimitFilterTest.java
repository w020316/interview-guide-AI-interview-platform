package com.example.interview.filter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import jakarta.servlet.DispatcherType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AiRateLimitFilter} 单元测试（P1-4）。
 *
 * <p>不启动 Spring 上下文：限流的行为面（路径命中、配额、限流键、SSE 单次计数）
 * 全部可以用 {@link MockHttpServletRequest} + {@link MockFilterChain} 精确驱动，
 * 起容器只会把「配额算错」这类问题掩盖成启动时长问题。
 *
 * <p>覆盖矩阵：
 * <ul>
 *   <li>路径：命中 AI 前缀才计数；健康检查/静态资源/普通 CRUD/相邻模块（{@code /api/jobs/}）不计数</li>
 *   <li>配额：超限拦截且响应符合 {@code docs/api-error-contract.md}</li>
 *   <li>键：已登录按 userId、匿名按 IP，且 A 打满不影响 B</li>
 *   <li>SSE：同一长连接的 ASYNC 派遣不重复计数</li>
 *   <li>分级：{@code /api/agent/} 用更严的独立配额，不吃普通 AI 配额</li>
 * </ul>
 */
@DisplayName("AiRateLimitFilter AI 端点按用户限流")
class AiRateLimitFilterTest {

    private static final String AGENT_PATH = "/api/agent/chat/stream";
    private static final String INTERVIEW_PATH = "/api/interview/questions";

    /** 公网 IP（不属于 ClientIpUtil 的受信私网段，因此会原样作为限流键） */
    private static final String IP_A = "203.0.113.7";
    private static final String IP_B = "203.0.113.8";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AiRateLimitFilter filter(int requests, int expensiveRequests) {
        return filter(true, requests, expensiveRequests);
    }

    private AiRateLimitFilter filter(boolean enabled, int requests, int expensiveRequests) {
        return new AiRateLimitFilter(enabled,
                AiRateLimitFilter.DEFAULT_AI_PATH_PREFIXES, requests,
                AiRateLimitFilter.DEFAULT_EXPENSIVE_PATH_PREFIXES, expensiveRequests,
                60L, objectMapper);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ─────────────────────────────── 测试脚手架 ───────────────────────────────

    private static MockHttpServletRequest request(String path, String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(ip);
        return request;
    }

    private static void loginAs(String userId) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    private static void anonymous() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "anonymous-key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
    }

    /** 跑一次过滤，返回「请求是否被放行到后续链路」 */
    private static boolean passThrough(AiRateLimitFilter filter,
                                       MockHttpServletRequest request,
                                       MockHttpServletResponse response) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        return chain.getRequest() != null;
    }

    // ───────────────────────────── 1. 路径命中 ─────────────────────────────

    @Nested
    @DisplayName("路径命中：只拦 AI 前缀")
    class PathMatching {

        @Test
        @DisplayName("AI 路径被计数：配额 1 时第 2 次必须被拦")
        void aiPaths_areCounted() throws Exception {
            AiRateLimitFilter filter = filter(1, 5);
            loginAs("1");

            assertThat(passThrough(filter, request(INTERVIEW_PATH, IP_A), new MockHttpServletResponse()))
                    .as("第 1 次应放行").isTrue();
            assertThat(passThrough(filter, request(INTERVIEW_PATH, IP_A), new MockHttpServletResponse()))
                    .as("第 2 次应被限流").isFalse();
        }

        @Test
        @DisplayName("健康检查/静态资源/普通 CRUD/相邻模块 一律不计数")
        void nonAiPaths_areNotCounted() throws Exception {
            AiRateLimitFilter filter = filter(1, 5);
            loginAs("1");

            String[] notLimited = {
                    "/api/health",              // 深度体检（监控高频探活，绝不能计入 AI 配额）
                    "/api/info",
                    "/actuator/health",
                    "/assets/index-abc.js",     // 静态资源
                    "/index.html",
                    "/api/knowledge/recent-questions",  // 纯数据库查询
                    "/api/knowledge/wrong-questions",
                    "/api/resume/history",              // 简历列表 CRUD
                    "/api/resume/12",                   // 简历详情
                    "/api/jobs/refresh",                // JobAgentController 自带 1/5min 限流
                    "/api/session/create",
                    "/api/todo/list",
            };
            for (String path : notLimited) {
                assertThat(passThrough(filter, request(path, IP_A), new MockHttpServletResponse()))
                        .as("%s 不应被 AI 限流拦截", path).isTrue();
            }

            // 关键：上面那批请求没有消耗配额 —— AI 路径的第 1 次仍然放行
            assertThat(passThrough(filter, request(INTERVIEW_PATH, IP_A), new MockHttpServletResponse()))
                    .as("非 AI 路径不得消耗 AI 配额").isTrue();
        }

        @Test
        @DisplayName("前缀按路径段边界匹配：/api/resume/analyzer-x 不被当成 /api/resume/analyze")
        void prefixMatchRespectsSegmentBoundary() throws Exception {
            AiRateLimitFilter filter = filter(1, 5);
            loginAs("1");

            // 先打满配额
            passThrough(filter, request(INTERVIEW_PATH, IP_A), new MockHttpServletResponse());

            // 名字相近但不属于 AI 端点：不应命中（命中就说明用了裸 startsWith）
            assertThat(passThrough(filter, request("/api/resume/analyzer-x", IP_A), new MockHttpServletResponse()))
                    .isTrue();
            // 而真正的 AI 子路径仍被拦（证明上面的通过不是因为整个前缀失效）
            assertThat(passThrough(filter, request("/api/resume/analyze", IP_A), new MockHttpServletResponse()))
                    .isFalse();
        }
    }

    // ───────────────────────────── 2. 超限拦截 ─────────────────────────────

    @Nested
    @DisplayName("超限拦截：响应口径遵循 api-error-contract.md")
    class OverLimit {

        @Test
        @DisplayName("超限返回 HTTP 200 + body code=429（不是真 4xx），并带 Retry-After")
        void overLimit_returnsContractCompliantBody() throws Exception {
            AiRateLimitFilter filter = filter(1, 5);
            loginAs("1");
            passThrough(filter, request(INTERVIEW_PATH, IP_A), new MockHttpServletResponse());

            MockHttpServletResponse response = new MockHttpServletResponse();
            boolean passed = passThrough(filter, request(INTERVIEW_PATH, IP_A), response);

            assertThat(passed).as("超限请求不得进入业务链路").isFalse();
            // 契约 §2：代码侧直接产出的业务错误 = HTTP 200 + body code≠200。
            // 改成 429 会让前端拦截器走 error 分支，用户只看到英文 status code 文案。
            assertThat(response.getStatus()).isEqualTo(200);
            JsonNode body = objectMapper.readTree(response.getContentAsString());
            assertThat(body.get("code").asInt()).isEqualTo(429);
            assertThat(body.get("message").asText()).contains("过于频繁").contains("上限 1 次/1 分钟");
            assertThat(body.get("data").isNull()).isTrue();
            assertThat(body.has("timestamp")).isTrue();
            assertThat(response.getHeader("Retry-After")).isEqualTo("60");
            assertThat(response.getHeader("X-Rate-Limit-Remaining")).isEqualTo("0");
        }

        @Test
        @DisplayName("enabled=false 时完全不拦截（灰度/排障开关）")
        void disabledFilter_neverBlocks() throws Exception {
            AiRateLimitFilter filter = filter(false, 1, 1);
            loginAs("1");

            for (int i = 0; i < 5; i++) {
                assertThat(passThrough(filter, request(AGENT_PATH, IP_A), new MockHttpServletResponse()))
                        .as("开关关闭时第 %d 次也应放行", i + 1).isTrue();
            }
        }
    }

    // ─────────────────────────────── 3. 限流键 ───────────────────────────────

    @Nested
    @DisplayName("限流键：已登录用 userId，匿名用 IP")
    class RateLimitKey {

        @Test
        @DisplayName("已登录：键为 user:<userId>，不是 anonymousUser")
        void loggedIn_keyIsUserId() {
            loginAs("42");
            assertThat(filter(1, 1).resolveKey(request(INTERVIEW_PATH, IP_A))).isEqualTo("user:42");
        }

        @Test
        @DisplayName("匿名令牌：键为 ip:<ip>（AnonymousAuthenticationToken.isAuthenticated() 恒 true，必须显式排除）")
        void anonymousToken_keyIsClientIp() {
            anonymous();
            assertThat(filter(1, 1).resolveKey(request(INTERVIEW_PATH, IP_A)))
                    .as("若用 != null 判断登录态，这里会退化成全站共用 user:anonymousUser")
                    .isEqualTo("ip:" + IP_A);
        }

        @Test
        @DisplayName("无认证信息 / 未认证 token：键为 ip:<ip>")
        void noAuthentication_keyIsClientIp() {
            SecurityContextHolder.clearContext();
            assertThat(filter(1, 1).resolveKey(request(INTERVIEW_PATH, IP_A))).isEqualTo("ip:" + IP_A);

            // 双参构造的 UsernamePasswordAuthenticationToken 的 isAuthenticated() 为 false
            SecurityContextHolder.getContext()
                    .setAuthentication(new UsernamePasswordAuthenticationToken("42", null));
            assertThat(filter(1, 1).resolveKey(request(INTERVIEW_PATH, IP_A))).isEqualTo("ip:" + IP_A);
        }

        @Test
        @DisplayName("已登录用户之间隔离：A 打满配额不影响 B")
        void loggedInUsers_areIsolated() throws Exception {
            AiRateLimitFilter filter = filter(2, 5);

            loginAs("1");
            assertThat(passThrough(filter, request(INTERVIEW_PATH, IP_A), new MockHttpServletResponse())).isTrue();
            assertThat(passThrough(filter, request(INTERVIEW_PATH, IP_A), new MockHttpServletResponse())).isTrue();
            assertThat(passThrough(filter, request(INTERVIEW_PATH, IP_A), new MockHttpServletResponse()))
                    .as("A 的第 3 次应被限流").isFalse();

            loginAs("2");
            assertThat(passThrough(filter, request(INTERVIEW_PATH, IP_A), new MockHttpServletResponse()))
                    .as("同一 IP 上的另一个登录用户不应被 A 的配额牵连").isTrue();
        }

        @Test
        @DisplayName("匿名按 IP 隔离：同一 IP 耗尽不影响其他 IP")
        void anonymousUsers_areIsolatedByIp() throws Exception {
            AiRateLimitFilter filter = filter(1, 5);
            anonymous();

            assertThat(passThrough(filter, request(INTERVIEW_PATH, IP_A), new MockHttpServletResponse())).isTrue();
            assertThat(passThrough(filter, request(INTERVIEW_PATH, IP_A), new MockHttpServletResponse()))
                    .as("同一 IP 第 2 次应被限流").isFalse();
            assertThat(passThrough(filter, request(INTERVIEW_PATH, IP_B), new MockHttpServletResponse()))
                    .as("另一个 IP 不应被牵连").isTrue();
        }
    }

    // ───────────────────────── 4. SSE 长连接单次计数 ─────────────────────────

    @Nested
    @DisplayName("SSE 长连接：只在建立连接时计一次")
    class SseSingleCount {

        @Test
        @DisplayName("ASYNC 派遣不重复计数：配额 1 时，分片推送不会把用户配额吃光")
        void asyncDispatch_isNotCounted() throws Exception {
            AiRateLimitFilter filter = filter(5, 1);
            loginAs("1");

            // ① 建立连接（REQUEST 派遣）——计 1 次，正好吃掉 agent 的全部配额
            MockHttpServletRequest request = request(AGENT_PATH, IP_A);
            assertThat(passThrough(filter, request, new MockHttpServletResponse())).isTrue();

            // ② 模拟流式推送过程中的多次 ASYNC 派遣：同一连接，不应再计数
            for (int i = 0; i < 5; i++) {
                MockHttpServletRequest asyncRequest = request(AGENT_PATH, IP_A);
                asyncRequest.setDispatcherType(DispatcherType.ASYNC);
                assertThat(passThrough(filter, asyncRequest, new MockHttpServletResponse()))
                        .as("第 %d 次 ASYNC 派遣不应被拦截", i + 1).isTrue();
            }

            // ③ 配额确实已被 ① 用尽：新连接才被拦 —— 证明 ② 没计数也没被放水绕过
            assertThat(passThrough(filter, request(AGENT_PATH, IP_A), new MockHttpServletResponse()))
                    .as("新连接应被限流").isFalse();
        }
    }

    // ───────────────────────── 5. 分级配额（Agent 更严） ─────────────────────────

    @Nested
    @DisplayName("分级配额：/api/agent/ 用独立的更严配额")
    class TieredQuota {

        @Test
        @DisplayName("Agent 与普通 AI 配额互不影响")
        void agentAndGeneralQuotasAreIndependent() throws Exception {
            AiRateLimitFilter filter = filter(5, 1);
            loginAs("1");

            // Agent：配额 1
            assertThat(passThrough(filter, request(AGENT_PATH, IP_A), new MockHttpServletResponse())).isTrue();
            assertThat(passThrough(filter, request(AGENT_PATH, IP_A), new MockHttpServletResponse()))
                    .as("Agent 第 2 次应被更严的 1 次/分钟拦住").isFalse();

            // 普通 AI：配额 5，未被 Agent 的耗尽拖累
            assertThat(passThrough(filter, request(INTERVIEW_PATH, IP_A), new MockHttpServletResponse()))
                    .as("普通 AI 端点应使用独立配额").isTrue();
        }

        @Test
        @DisplayName("Agent 路径不会同时消耗普通配额（避免双重计数）")
        void agentPath_doesNotConsumeGeneralQuota() throws Exception {
            AiRateLimitFilter filter = filter(1, 5);
            loginAs("1");

            // Agent 打 3 次（配额 5，全部放行）
            for (int i = 0; i < 3; i++) {
                assertThat(passThrough(filter, request(AGENT_PATH, IP_A), new MockHttpServletResponse())).isTrue();
            }

            // 普通配额 1 次：若 Agent 请求也记进了普通桶，这里会被立刻拦住
            assertThat(passThrough(filter, request(INTERVIEW_PATH, IP_A), new MockHttpServletResponse()))
                    .as("普通 AI 配额仍是完整额度").isTrue();
        }
    }

    @Nested
    @DisplayName("执行顺序")
    class FilterOrder {

        @Test
        @DisplayName("必须晚于 Spring Security 链（默认 -100），否则读不到 userId")
        void orderIsAfterSecurityChain() {
            assertThat(AiRateLimitFilter.FILTER_ORDER).isGreaterThan(-100);
        }
    }

    @Nested
    @DisplayName("配置容错：写错不等于放行，也不等于起不来")
    class ConfigurationFallback {

        @Test
        @DisplayName("配额/窗口配成 0 或负数时回退默认值（60 / 20 / 60s），不会退化成「不限流」")
        void invalidQuotaFallsBackToDefaults() throws Exception {
            // PerUserRateLimiter 对 <=0 直接抛异常；过滤器必须先兜住，否则一个手滑的
            // AI_USER_RATE_LIMIT_REQUESTS=0 就会让整个应用起不来
            AiRateLimitFilter filter = new AiRateLimitFilter(true,
                    AiRateLimitFilter.DEFAULT_AI_PATH_PREFIXES, 0,
                    AiRateLimitFilter.DEFAULT_EXPENSIVE_PATH_PREFIXES, -5,
                    0L, objectMapper);
            loginAs("1");

            // Agent 走默认 20 次/分钟
            for (int i = 0; i < 20; i++) {
                assertThat(passThrough(filter, request(AGENT_PATH, IP_A), new MockHttpServletResponse()))
                        .as("第 %d 次应在默认 20 次配额内", i + 1).isTrue();
            }
            assertThat(passThrough(filter, request(AGENT_PATH, IP_A), new MockHttpServletResponse()))
                    .as("第 21 次应被默认配额拦住（说明不是「无限放行」）").isFalse();
        }

        @Test
        @DisplayName("路径配置为空白时过滤器完全不生效（显式关闭的一种方式）")
        void blankPathConfigurationDisablesFiltering() throws Exception {
            AiRateLimitFilter filter = new AiRateLimitFilter(true, "  ", 1, "", 1, 60L, objectMapper);
            loginAs("1");

            for (int i = 0; i < 3; i++) {
                assertThat(passThrough(filter, request(AGENT_PATH, IP_A), new MockHttpServletResponse())).isTrue();
            }
        }

        @Test
        @DisplayName("非整分钟窗口的文案为「N 秒」")
        void nonMinuteWindowIsRenderedInSeconds() throws Exception {
            AiRateLimitFilter filter = new AiRateLimitFilter(true,
                    AiRateLimitFilter.DEFAULT_AI_PATH_PREFIXES, 1,
                    AiRateLimitFilter.DEFAULT_EXPENSIVE_PATH_PREFIXES, 1,
                    30L, objectMapper);
            loginAs("1");
            passThrough(filter, request(INTERVIEW_PATH, IP_A), new MockHttpServletResponse());

            MockHttpServletResponse response = new MockHttpServletResponse();
            passThrough(filter, request(INTERVIEW_PATH, IP_A), response);

            JsonNode body = objectMapper.readTree(response.getContentAsString());
            assertThat(body.get("message").asText()).contains("上限 1 次/30 秒");
            assertThat(response.getHeader("Retry-After")).isEqualTo("30");
        }
    }
}
