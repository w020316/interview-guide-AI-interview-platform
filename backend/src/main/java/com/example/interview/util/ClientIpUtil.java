package com.example.interview.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端 IP 解析工具（限流与登录锁定共用）
 *
 * v1.23.1 安全修复（BE-04/P1）：
 * 此前取 X-Forwarded-For 最左值——该值是客户端可自行伪造的第一个条目，
 * 而生产部署（Render）直连 IP 恒为代理内网地址、必然被视为受信代理，
 * 导致攻击者伪造 XFF 即可轮换 IP 绕过限流与登录失败锁定。
 *
 * 正确策略：从 XFF 右端向左查找第一个非受信代理的 IP——
 * 右侧条目由最靠近服务端的代理追加，客户端无法篡改该位置。
 * 全部条目均为受信代理时回退 remoteAddr。
 */
public final class ClientIpUtil {

    private ClientIpUtil() {
    }

    public static String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank() || !isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }
        String[] parts = forwarded.split(",");
        for (int i = parts.length - 1; i >= 0; i--) {
            String candidate = parts[i].trim();
            if (!candidate.isEmpty() && !isTrustedProxy(candidate)) {
                return candidate;
            }
        }
        return remoteAddr;
    }

    /** 判断是否为受信代理（内网/回环地址） */
    static boolean isTrustedProxy(String ip) {
        if (ip == null) return false;
        if (ip.startsWith("10.")
                || ip.startsWith("192.168.")
                || ip.equals("127.0.0.1")
                || ip.startsWith("::1")) {
            return true;
        }
        // 精确校验 172.16.0.0/12（172.16.0.0 - 172.31.255.255），避免 172.* 误判
        if (ip.startsWith("172.")) {
            try {
                int second = Integer.parseInt(ip.split("\\.")[1]);
                return second >= 16 && second <= 31;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }
}
