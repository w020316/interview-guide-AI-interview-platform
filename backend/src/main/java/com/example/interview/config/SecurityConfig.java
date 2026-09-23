package com.example.interview.config;

import com.example.interview.security.JwtAuthFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

/**
 * Spring Security 配置
 * - 无状态 JWT 模式（不使用 Session）
 * - /api/auth/** 公开访问
 * - Actuator、Swagger 公开访问
 * - 其余接口需携带有效 Bearer token
 * - v1.31.4：@EnableMethodSecurity 启用 @PreAuthorize（/api/admin/** 仅 ROLE_ADMIN）
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Autowired
    private JwtAuthFilter jwtAuthFilter;

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
                // 认证接口公开
                .requestMatchers("/api/auth/**").permitAll()
                // 系统信息公开（轻量探活；深度体检在 /api/health/detail，走 anyRequest 认证）
                .requestMatchers("/api/info", "/api/health").permitAll()
                // Swagger UI & OpenAPI 公开
                .requestMatchers(
                    "/swagger-ui.html", "/swagger-ui/**",
                    "/v3/api-docs/**", "/v3/api-docs"
                ).permitAll()
                // Actuator 健康检查公开（prometheus/metrics 需认证）
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
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
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = Arrays.stream(corsAllowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        config.setAllowedOriginPatterns(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        // 2026-09-19 真机回归修复：补 Cache-Control。
        // 跨域下 Cache-Control 非 CORS 安全列表头，浏览器会先发 OPTIONS 预检；
        // 此前白名单未包含它 → 预检 403 → 前端唤醒器探测被浏览器拦截、永远重试失败。
        // 这里做兜底放行，前端亦已改为不发送该头（用 URL 时间戳做缓存失效）。
        config.setAllowedHeaders(List.of(
                "Authorization", "Content-Type", "X-Requested-With", "Accept", "Cache-Control"));
        config.setExposedHeaders(List.of("X-Rate-Limit-Remaining"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
