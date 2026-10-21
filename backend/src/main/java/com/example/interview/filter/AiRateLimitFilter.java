package com.example.interview.filter;

import com.example.interview.common.Result;
import com.example.interview.util.ClientIpUtil;
import com.example.interview.util.PerUserRateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * AI 端点按用户配额限流过滤器（P1-4，2026-10-21 质量循环）。
 *
 * <h2>要解决的问题</h2>
 *
 * <p>项目里早就有 {@link PerUserRateLimiter}，但全仓只有 4 处使用者
 * （注册 1/h、改密 1/h、面试 AI 30/min、岗位刷新 1/5min）。真正的高成本 AI 入口
 * <b>完全裸奔</b>：{@code AgentController} 有 11 处 AI 调用，且它是 ReAct 循环
 * （{@code AgentService.MAX_ROUNDS = 8}），<b>一次 HTTP 请求可能触发 8 轮 × 多次工具调用</b>，
 * 是全站成本最高的入口。单个账号在客户端循环刷它，就能把厂商许可（免费档 20 RPM）打满，
 * 触发全站降级甚至 503。
 *
 * <h2>为什么是「按路径前缀统一挂载」而不是逐接口加注解</h2>
 *
 * <p>这个项目最典型的缺陷模式是「<b>清单跟不上演进</b>」：限流器只挂在当时想到的 4 个方法上，
 * 后来新增的 AI 端点默认裸奔，而没人会记得回头补。逐接口加 {@code @PreAuthorize} 式的注解
 * 只会重演同一种病。本过滤器挂在**模块路径前缀**上（{@code /api/agent/}、{@code /api/interview/} …），
 * 该模块以后新增的任何 AI 端点自动被覆盖，无需改代码。
 *
 * <h2>限流键</h2>
 *
 * <p>已登录用 {@code userId}，匿名用客户端 IP。判断登录态必须显式排除
 * {@link AnonymousAuthenticationToken}：Spring Security 的匿名令牌
 * {@code isAuthenticated()} <b>恒为 true</b>、{@code principal} 是字符串 {@code "anonymousUser"}，
 * 用 {@code authentication != null} 判断会让<b>所有匿名请求共用同一个限流桶</b>
 * （key = anonymousUser），一个匿名攻击者即可打满全站匿名配额 —— 这正是 G5 已踩过的坑。
 *
 * <h2>SSE 长连接只计一次</h2>
 *
 * <p>{@code /api/agent/chat/stream} 与 {@code /api/interview/ask/stream} 是
 * {@code text/event-stream} 长连接。{@link OncePerRequestFilter} 保证同一请求只过滤一次，
 * 且 {@link #shouldNotFilterAsyncDispatch()} 恒为 {@code true}，因此连接建立后
 * 分片推送（以及收尾的 ASYNC dispatch）都不会重复计数 —— 否则一个正常的长回答
 * 就能把用户配额吃光。
 *
 * <h2>超限响应口径</h2>
 *
 * <p>遵循 {@code docs/api-error-contract.md} §2：本项目的业务错误由代码侧直接产出时，
 * <b>HTTP 保持 200，语义由 body 的 {@code code} 承载</b>（429 = 限流）。
 * 前端拦截器（{@code frontend/src/api/index.ts}）只在 HTTP 2xx 分支读 {@code body.code}
 * 并展示后端文案；若这里改成真 4xx，用户看到的会是英文的
 * {@code Request failed with status code 429}，违反契约 §5「429 应展示限流提示」。
 *
 * <h2>排除范围</h2>
 *
 * <p>只拦配置里列出的 AI 前缀。健康检查（{@code /api/health}、{@code /actuator/**}）、
 * 静态资源（{@code /assets/**}、{@code index.html}）、SSE 心跳（不产生新请求）、
 * 以及知识库/简历模块里的普通 CRUD 端点（{@code /api/knowledge/recent-questions}、
 * {@code /api/resume/history} 等）均不计数。
 */
public class AiRateLimitFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(AiRateLimitFilter.class);

    /**
     * Servlet 过滤器顺序：Spring Security 过滤器链默认 order = {@code -100}。
     *
     * <p>本过滤器必须排在其<b>后</b>，原因有二：
     * <ol>
     *   <li>只有排在 {@code JwtAuthFilter} 之后，才能从 SecurityContext 读到已解析的 userId；</li>
     *   <li>未认证请求会先被 Security 拦成 401，不会消耗任何 AI 配额
     *       （否则匿名者可用垃圾请求耗尽某个 IP 的额度）。</li>
     * </ol>
     */
    public static final int FILTER_ORDER = -90;

    /**
     * 默认受限流的 AI 路径前缀（逗号分隔，可被 {@code app.ai.user-rate-limit.paths} 覆盖）。
     *
     * <p>取值依据：逐个核对控制器的真实映射，只收录「一次请求会打 AI/embedding」的路径。
     * <ul>
     *   <li>{@code /api/agent/} —— AgentController（ReAct 循环，另见更严的 expensive 配额）</li>
     *   <li>{@code /api/interview/} —— InterviewController 全部 5 个端点均为 AI
     *       （questions/evaluate/followup/upload-image/ask-stream），模块内无 CRUD 端点</li>
     *   <li>{@code /api/job/} —— JobAnalysisController 全部 4 个端点均为 AI
     *       （analyze/gap/letter/import-url），模块内无 CRUD 端点</li>
     *   <li>{@code /api/knowledge/ask|import|search} —— 走 LLM 或 embedding；
     *       {@code recent-questions} / {@code wrong-questions} / {@code question-summary}
     *       是纯数据库查询，<b>刻意不收录</b>，避免正常浏览被误伤</li>
     *   <li>{@code /api/resume/analyze|upload|optimize|import-url}、{@code /autofill}
     *       —— 简历解析/优化为 LLM 调用；{@code /api/resume/history}、{@code /api/resume/{id}}
     *       等 CRUD 不收录</li>
     * </ul>
     *
     * <p>⚠️ 本常量必须保持「编译期常量」（只用字面量与 {@code +} 拼接），
     * 因为 {@code AiRateLimitConfig} 把它当作 {@code @Value} 的默认值使用。
     */
    public static final String DEFAULT_AI_PATH_PREFIXES = "/api/agent/"
            + ",/api/interview/"
            + ",/api/job/"
            + ",/api/knowledge/ask"
            + ",/api/knowledge/import"
            + ",/api/knowledge/search"
            + ",/api/resume/analyze"
            + ",/api/resume/upload"
            + ",/api/resume/optimize"
            + ",/api/resume/import-url"
            + ",/autofill";

    /** 默认「成本最高」的路径前缀（可被 {@code app.ai.user-rate-limit.expensive-paths} 覆盖） */
    public static final String DEFAULT_EXPENSIVE_PATH_PREFIXES = "/api/agent/";

    /** 默认每窗口配额：普通 AI 端点 60 次/分钟 */
    public static final int DEFAULT_AI_REQUESTS = 60;

    /** 默认每窗口配额：Agent 端点 20 次/分钟（一次请求可能烧 8 轮 AI） */
    public static final int DEFAULT_EXPENSIVE_REQUESTS = 20;

    /** 默认窗口长度：60 秒 */
    public static final long DEFAULT_WINDOW_SECONDS = 60L;

    /** 已登录用户的限流键前缀（与 IP 键隔离，避免 userId 撞上 IP 字符串） */
    static final String KEY_PREFIX_USER = "user:";

    /** 匿名的限流键前缀 */
    static final String KEY_PREFIX_IP = "ip:";

    /** 匿名令牌的 principal 常量（Spring Security 固定值） */
    private static final String ANONYMOUS_PRINCIPAL = "anonymousUser";

    private final boolean enabled;
    private final List<String> aiPathPrefixes;
    private final List<String> expensivePathPrefixes;
    private final int aiRequests;
    private final int expensiveRequests;
    private final long windowSeconds;
    private final PerUserRateLimiter aiLimiter;
    private final PerUserRateLimiter expensiveLimiter;
    private final ObjectMapper objectMapper;

    /**
     * @param enabled                是否启用（{@code app.ai.user-rate-limit.enabled}）
     * @param aiPathPrefixes         受限流的 AI 路径前缀，逗号分隔
     * @param aiRequests             普通 AI 端点每窗口配额（&lt;=0 时回退默认 60）
     * @param expensivePathPrefixes  成本更高的路径前缀，逗号分隔
     * @param expensiveRequests      高成本端点每窗口配额（&lt;=0 时回退默认 20）
     * @param windowSeconds          窗口长度秒数（&lt;=0 时回退默认 60）
     * @param objectMapper           用于按 {@code Result} 结构序列化错误体
     */
    public AiRateLimitFilter(boolean enabled,
                             String aiPathPrefixes,
                             int aiRequests,
                             String expensivePathPrefixes,
                             int expensiveRequests,
                             long windowSeconds,
                             ObjectMapper objectMapper) {
        this.enabled = enabled;
        this.aiPathPrefixes = parsePrefixes(aiPathPrefixes);
        this.expensivePathPrefixes = parsePrefixes(expensivePathPrefixes);
        // 配置写错不应让整个应用起不来（这套项目里配置兜底是既定风格），
        // 但也不能退化成 0 —— PerUserRateLimiter 对 <=0 直接抛异常。
        this.aiRequests = aiRequests > 0 ? aiRequests : DEFAULT_AI_REQUESTS;
        this.expensiveRequests =
                expensiveRequests > 0 ? expensiveRequests : DEFAULT_EXPENSIVE_REQUESTS;
        this.windowSeconds = windowSeconds > 0 ? windowSeconds : DEFAULT_WINDOW_SECONDS;
        this.aiLimiter = new PerUserRateLimiter(this.aiRequests, this.windowSeconds * 1000L);
        this.expensiveLimiter =
                new PerUserRateLimiter(this.expensiveRequests, this.windowSeconds * 1000L);
        this.objectMapper = objectMapper;
        log.info("AI 端点限流已装配：enabled={} 普通 {} 次/{}s（{} 个前缀），高成本 {} 次/{}s（{} 个前缀）",
                enabled, this.aiRequests, this.windowSeconds, this.aiPathPrefixes.size(),
                this.expensiveRequests, this.windowSeconds, this.expensivePathPrefixes.size());
    }

    @Override
    public int getOrder() {
        return FILTER_ORDER;
    }

    /**
     * ASYNC dispatch 一律不过滤。
     *
     * <p>SSE 请求在容器侧会经历「REQUEST → ASYNC（多次）→ 完成」的派遣序列，
     * 只有最初的 REQUEST 派遣代表用户发起了一次调用。这里显式返回 {@code true}
     * 把「长连接只计一次」写死，也避免将来有人把默认值改掉时悄悄引入重复计数。
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return true;
    }

    /**
     * 只对配置中列出的 AI 前缀生效。
     *
     * <p>返回 {@code true} 时 {@link jakarta.servlet.FilterChain} 直接放行，不产生任何计数 ——
     * 健康检查、静态资源、普通 CRUD 全走这条路径。
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled) {
            return true;
        }
        String path = resolvePath(request);
        return !matchesAny(path, aiPathPrefixes) && !matchesAny(path, expensivePathPrefixes);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = resolvePath(request);
        boolean expensive = matchesAny(path, expensivePathPrefixes);
        PerUserRateLimiter limiter = expensive ? expensiveLimiter : aiLimiter;
        int quota = expensive ? expensiveRequests : aiRequests;
        String key = resolveKey(request);

        if (!limiter.allow(key)) {
            log.warn("AI 端点限流命中：path={} key={} 配额={} 次/{} 秒", path, key, quota, windowSeconds);
            writeRateLimited(response, quota);
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 解析限流键：已登录 → userId；匿名 → 客户端 IP。
     *
     * <p>{@code anonymousUser} 会被显式排掉（见类注释），保证匿名请求按 IP 分桶，
     * 而不是全站共用一个桶。
     */
    String resolveKey(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String userId = extractUserId(authentication);
        if (StringUtils.hasText(userId)) {
            return KEY_PREFIX_USER + userId;
        }
        String ip = ClientIpUtil.resolve(request);
        if (!StringUtils.hasText(ip)) {
            // remoteAddr 理论上不会为空，但限流键为空会让限流器直接拒绝全部请求，
            // 属于「宁可少限流也不能误杀」的方向，故给一个确定的兜底键。
            ip = "unknown";
        }
        return KEY_PREFIX_IP + ip;
    }

    /**
     * 从认证信息中提取用户 id；未登录/匿名一律返回 {@code null}。
     *
     * <p>兼容两种 principal：本项目 {@code JwtAuthFilter} 写入的是 userId 字符串，
     * 而标准表单登录写入的是 {@link UserDetails}。
     */
    private static String extractUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        if (authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        String candidate = null;
        if (principal instanceof UserDetails userDetails) {
            candidate = userDetails.getUsername();
        } else if (principal instanceof String text) {
            candidate = text;
        }
        if (!StringUtils.hasText(candidate) || ANONYMOUS_PRINCIPAL.equals(candidate)) {
            return null;
        }
        return candidate;
    }

    /**
     * 写出超限响应。
     *
     * <p>HTTP 状态保持 200：契约（{@code docs/api-error-contract.md} §2）把
     * 「代码侧直接产出业务错误」定义为 HTTP 200 + body {@code code != 200}，
     * 前端只在 2xx 分支读 {@code body.code} 并展示后端文案。
     */
    private void writeRateLimited(HttpServletResponse response, int quota) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", String.valueOf(windowSeconds));
        response.setHeader("X-Rate-Limit-Remaining", "0");
        objectMapper.writeValue(response.getWriter(),
                Result.error(429, "AI 调用过于频繁（上限 " + quota + " 次/" + windowText() + "），请稍后再试"));
        response.flushBuffer();
    }

    /** 人类可读的窗口长度（60 → 「1 分钟」） */
    private String windowText() {
        if (windowSeconds >= 60 && windowSeconds % 60 == 0) {
            return (windowSeconds / 60) + " 分钟";
        }
        return windowSeconds + " 秒";
    }

    /** 取应用内路径（去掉 contextPath 与 path parameter 尾巴） */
    private static String resolvePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return "";
        }
        String contextPath = request.getContextPath();
        if (StringUtils.hasText(contextPath) && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        // 例如 /api/agent/chat/stream;jsessionid=xxx —— 分号之后不是路径的一部分
        int semicolon = uri.indexOf(';');
        if (semicolon >= 0) {
            uri = uri.substring(0, semicolon);
        }
        return uri;
    }

    /**
     * 前缀匹配（按路径**段**边界匹配）。
     *
     * <p>刻意不用裸 {@code startsWith}：{@code /api/resume/analyze} 不应匹配
     * {@code /api/resume/analyzer-x}，否则路径语义一变就会误伤无关接口。
     */
    private static boolean matchesAny(String path, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (matches(path, prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(String path, String prefix) {
        if (path == null || prefix == null || prefix.isEmpty()) {
            return false;
        }
        String base = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        if (base.isEmpty()) {
            return false;
        }
        if (path.equals(base)) {
            return true;
        }
        return path.length() > base.length()
                && path.startsWith(base)
                && path.charAt(base.length()) == '/';
    }

    private static List<String> parsePrefixes(String commaSeparated) {
        if (!StringUtils.hasText(commaSeparated)) {
            return List.of();
        }
        return Arrays.stream(commaSeparated.split(","))
                .map(String::trim)
                .filter(segment -> !segment.isEmpty())
                .toList();
    }
}
