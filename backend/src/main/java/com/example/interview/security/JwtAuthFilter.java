package com.example.interview.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

    @Autowired
    private JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String token = extractToken(request);

        if (StringUtils.hasText(token) && jwtUtil.isValid(token)) {
            String userId = jwtUtil.extractUserId(token);
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

    /** 从 Authorization: Bearer <token> 中提取 token */
    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
