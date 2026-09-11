package com.example.interview.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 角色/权限判断工具（v1.31.4 管理员体系）
 *
 * <p>管理员身份在登录时按配置的 {@code app.admin-usernames} 名单签发进 JWT 的 role claim，
 * 过滤器据此写入 {@code ROLE_ADMIN} 权限。本类供各 Controller/Service 统一判断，
 * 管理员无限制（如绕过刷新限流），普通用户保持既有行为。
 */
public final class RoleUtil {

    private RoleUtil() {
    }

    /** 当前请求用户是否为管理员 */
    public static boolean isCurrentUserAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}