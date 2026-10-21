package com.example.interview.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 认证过滤器
 * - 每次请求执行一次，从 Authorization 头提取 Bearer token
 * - 验证通过则写入 SecurityContext
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    @Autowired
    private JwtUtil jwtUtil;

    /**
     * 令牌版本缓存（P1-2，2026-10-21）：替代原 jti 黑名单。
     * required=false 以兼容 @WebMvcTest 切片（不加载 security 包 bean），缺失时跳过版本校验。
     */
    @Autowired(required = false)
    private TokenVersionCache tokenVersionCache;

    /**
     * v1.31.4 管理后台：用户禁用注册表。
     * required=false 以兼容 @WebMvcTest 切片（不加载 service bean），无 registry 时跳过禁用检查。
     */
    @Autowired(required = false)
    private com.example.interview.service.UserBanRegistry userBanRegistry;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String token = extractToken(request);

        if (StringUtils.hasText(token) && jwtUtil.isValid(token)) {
            String userId = jwtUtil.extractUserId(token);
            // P1-2（2026-10-21）：令牌版本校验。登出/改密/封禁会把 users.token_version +1，
            // 于是「此前签发的所有 token」立即失效（JWT 本身不可撤销，只能这样比对）。
            // token 无 ver claim（升级前签发）时按 DEFAULT_TOKEN_VERSION(=1) 处理 ——
            // 与 DB 默认值一致，保证升级瞬间不踢掉任何人；一旦该用户触发过一次 +1，旧 token 立刻失效。
            if (!versionMatches(token, userId)) {
                filterChain.doFilter(request, response);
                return;
            }
            // v1.31.4 管理后台：被禁用的用户即使 token 有效也拒绝访问（registry 缺失时跳过）
            if (userBanRegistry != null && userId != null && userBanRegistry.isBanned(parseId(userId))) {
                filterChain.doFilter(request, response);
                return;
            }
            String role = jwtUtil.extractRole(token);
            if (role == null || role.isBlank()) {
                role = "ROLE_USER"; // 兜底：无角色一律视为普通用户
            }
            // v1.31.4：按 JWT 中 role claim 赋权（ROLE_ADMIN / ROLE_USER），支持管理员无限制操作；principal 仍为 userId
            java.util.List<org.springframework.security.core.GrantedAuthority> authorities =
                    java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(role));
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(userId, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 令牌版本是否仍然有效（P1-2）。
     *
     * <p>三重兼容，缺一都会导致「升级即全员掉线」或「切片测试集体失败」：
     * <ol>
     *   <li>{@code tokenVersionCache == null}（{@code @WebMvcTest} 切片）→ 跳过校验；</li>
     *   <li>token 无 {@code ver} claim（升级前签发）→ 按 {@link JwtUtil#DEFAULT_TOKEN_VERSION}
     *       （=1）比对，与 DB 默认值一致，故升级瞬间不踢人；</li>
     *   <li>查不到当前版本（用户不存在 / DB 抖动）→ 跳过校验，不把偶发故障放大成全站 401。</li>
     * </ol>
     */
    private boolean versionMatches(String token, String userId) {
        if (tokenVersionCache == null) {
            return true;
        }
        Long id = parseId(userId);
        if (id == null) {
            // 非数字 subject：历史/异常 token，交给后续授权逻辑处理
            return true;
        }
        Integer current = tokenVersionCache.currentVersion(id);
        if (current == null) {
            return true;
        }
        Integer claimed = jwtUtil.extractTokenVersion(token);
        int effective = claimed == null ? JwtUtil.DEFAULT_TOKEN_VERSION : claimed;
        if (effective != current) {
            // 与既有 jti 分支一致：不认证，但继续走过滤链，由授权规则给出 401
            log.debug("token 版本已失效：userId={} token.ver={} db.ver={}", id, effective, current);
            return false;
        }
        return true;
    }

    /** 从 Authorization: Bearer <token> 中提取 token */
    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }

    /** 将 userId 字符串安全解析为 Long；非数字返回 null（视为未禁用） */
    private Long parseId(String userId) {
        try {
            return Long.valueOf(userId);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
