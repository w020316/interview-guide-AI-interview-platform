package com.example.interview.config;

import com.example.interview.security.JwtAuthFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Spring Security 配置
 * - 无状态 JWT 模式（不使用 Session）
 * - /api/auth/** 公开访问
 * - Actuator、Swagger 公开访问
 * - 其余接口需携带有效 Bearer token
 * - v1.31.4：@EnableMethodSecurity 启用 @PreAuthorize（/api/admin/** 仅 ROLE_ADMIN）
 * - F-13（2026-10-21）：CORS 关闭凭证携带；prod profile 强制剔除 localhost 源
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Autowired
    private JwtAuthFilter jwtAuthFilter;

    /**
     * 运行环境（F-13）。
     *
     * <p>用于在 <b>prod profile</b> 下从 CORS 白名单中剔除本地开发源。
     * 之所以要在代码里再挡一道、而不是只改配置文件：{@code CORS_ALLOWED_ORIGINS}
     * 是部署期可被覆盖的环境变量，一次误填 {@code http://localhost:5173} 就会把
     * 本地源带进生产白名单；配置层防护无法阻止这种误操作。
     */
    private final Environment environment;

    public SecurityConfig(Environment environment) {
        this.environment = environment;
    }

    /**
     * CORS 允许的前端来源（逗号分隔）
     * v1.11 起从 app.cors.allowed-origins 配置读取，修改部署域名无需改代码
     */
    @Value("${app.cors.allowed-origins}")
    private String corsAllowedOrigins;

    /**
     * 额外受信代理的 IP 前缀（逗号分隔，P1-02）。
     *
     * <p>在 Render 等平台上，应用看到的 remoteAddr 是边缘节点的<b>公网 IP</b>，
     * 不属于 {@link com.example.interview.util.ClientIpUtil} 默认信任的私网段，
     * 导致 X-Forwarded-For 永不生效、客户端真实 IP 解析失败，
     * 按 IP 的注册限流与登录锁定随之失效（2026-09-23 生产实测）。
     *
     * <p>默认留空 = 保持「仅信任私网代理」的保守策略，不会因本次改动放宽信任边界。
     * 需要修复时，在部署环境把平台代理的公网段填入该配置即可。
     */
    @Value("${app.security.trusted-proxy-prefixes:}")
    private String trustedProxyPrefixes;

    @jakarta.annotation.PostConstruct
    void configureClientIpTrust() {
        com.example.interview.util.ClientIpUtil.setExtraTrustedPrefixes(trustedProxyPrefixes);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // 未认证请求返回 401 + JSON（默认行为是 403，前端无法区分"无 token"和"权限不足"）
            .exceptionHandling(eh -> eh
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    response.getWriter().write(
                        "{\"code\":401,\"message\":\"登录已过期或未登录，请重新登录\",\"data\":null}");
                })
            )
            .authorizeHttpRequests(auth -> auth
                // P2-07：/api/auth/me 必须先于 /api/auth/** 声明为 authenticated——
                // 此前落在 permitAll 内，匿名请求经 AnonymousAuthenticationToken 也返回 200 假身份，
                // 被禁用用户则返回 banned:false，与该接口展示禁用状态的目的矛盾
                .requestMatchers("/api/auth/me").authenticated()
                // G5（2026-10-18）：/api/auth/change-password 同理必须先于 /api/auth/**。
                // 线上指纹复验实测：未加这条时，匿名请求经 AnonymousAuthenticationToken
                // （isAuthenticated() 恒为 true、principal 是字符串 "anonymousUser"）通过了
                // 控制器里的认证判断，返回 400「新密码长度需 6-64 字符」—— 语义完全错误，
                // 且会让所有匿名请求共用同一个限流桶（key=anonymousUser）。
                .requestMatchers("/api/auth/change-password").authenticated()
                // 认证接口公开
                .requestMatchers("/api/auth/**").permitAll()
                // 系统信息公开（轻量探活；深度体检在 /api/health/detail，走 anyRequest 认证）
                .requestMatchers("/api/info", "/api/health").permitAll()
                // Swagger UI & OpenAPI 公开
                .requestMatchers(
                    "/swagger-ui.html", "/swagger-ui/**",
                    "/v3/api-docs/**", "/v3/api-docs"
                ).permitAll()
                // Actuator 健康检查公开（info 轻量；其余需认证）
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                // R10-F02（v1.66.1）：/actuator/metrics 暴露 JVM / HTTP / 缓存 / SSE 水位等
                // **运营内部指标**，只对管理员开放。此前是 authenticated() —— 任何已登录的
                // 普通用户都能读到这些数据（对终端用户无任何价值，只增加信息暴露面）。
                // ⚠️ 这是对 v1.13.0「metrics 需认证」这一约定的**收紧**，SecurityConfigTest 已同步。
                .requestMatchers("/actuator/metrics", "/actuator/metrics/**").hasRole("ADMIN")
                .requestMatchers("/actuator/**").authenticated()
                // 其他所有接口需认证
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 跨域配置：白名单制，来源从 app.cors.allowed-origins 读取（逗号分隔）
     * - 使用 allowedOriginPatterns 支持通配（如 http://localhost:*）
     * - 修改部署域名时改环境变量 CORS_ALLOWED_ORIGINS 即可，无需改代码
     * - F-13（2026-10-21）：关闭凭证携带；prod profile 剔除 localhost 源
     *
     * <p>为什么关掉 {@code allowCredentials}：本项目鉴权走 {@code Authorization: Bearer}，
     * token 存在各自 origin 的 localStorage 里，<b>不依赖任何 Cookie</b>
     * （已 grep 确认前端全仓无 {@code withCredentials}、无 {@code document.cookie}/
     * {@code js-cookie}、无 Cookie 鉴权逻辑；{@code frontend/src/api/index.ts}
     * 亦明确记录「鉴权基于 Authorization: Bearer 头」）。开启凭证携带只会在被 XSS
     * 或子域接管时放大风险，收益为零。
     *
     * <p>为什么 prod 要剔除 localhost：{@code http://localhost:5173} 是开发者本机端口，
     * 生产环境没有任何合法来源会用它；而它一旦进入生产白名单，配合任意使用者本机
     * 起一个同名端口的服务，就可能形成「生产 API 信任本机页面」的边界混淆。
     * dev/local profile 仍保留它以保证本地联调便利。
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", buildCorsConfiguration());
        return source;
    }

    /**
     * 构建实际生效的 CORS 配置（独立方法以便单元测试直接断言，无需起 MockMvc）。
     */
    CorsConfiguration buildCorsConfiguration() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = Arrays.stream(corsAllowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (isProdProfile()) {
            List<String> filtered = origins.stream().filter(origin -> !isLocalOnlyOrigin(origin)).toList();
            if (filtered.size() != origins.size()) {
                log.info("prod profile 已从 CORS 白名单剔除本地开发源：剔除 {} 个，生效 {} 个",
                        origins.size() - filtered.size(), filtered.size());
            }
            origins = filtered;
        }
        config.setAllowedOriginPatterns(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        // 2026-09-19 真机回归修复：补 Cache-Control。
        // 跨域下 Cache-Control 非 CORS 安全列表头，浏览器会先发 OPTIONS 预检；
        // 此前白名单未包含它 → 预检 403 → 前端唤醒器探测被浏览器拦截、永远重试失败。
        // 这里做兜底放行，前端亦已改为不发送该头（用 URL 时间戳做缓存失效）。
        config.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "X-Requested-With", "Accept", "Cache-Control"));
        config.setExposedHeaders(List.of("X-Rate-Limit-Remaining"));
        // F-13：本服务不使用 Cookie/Session（STATELESS + Bearer token），
        // 允许携带凭证只会扩大 CSRF/XSS 面，没有任何功能依赖它。
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        return config;
    }

    /** 当前是否运行在 prod profile 下（由 {@code SPRING_PROFILES_ACTIVE=prod} 激活） */
    private boolean isProdProfile() {
        return environment != null && environment.acceptsProfiles(Profiles.of("prod"));
    }

    /**
     * 判断是否「仅本地可达」的来源。
     *
     * <p>覆盖 localhost / 127.0.0.1 / [::1] / 0.0.0.0 四种写法，
     * 以及 {@code http://localhost:*} 这类通配形态。
     */
    private static boolean isLocalOnlyOrigin(String origin) {
        String normalized = origin.toLowerCase(Locale.ROOT);
        return normalized.contains("localhost")
                || normalized.contains("127.0.0.1")
                || normalized.contains("[::1]")
                || normalized.contains("0.0.0.0");
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
