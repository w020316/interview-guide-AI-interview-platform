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

    /**
     * 额外受信代理的 IP 前缀（如平台边缘节点的公网段），由配置注入。
     *
     * <p><b>为什么需要它</b>：原实现只把 RFC1918 私网段与回环地址视为受信代理。
     * 但在 Render 这类平台上，应用看到的 {@code remoteAddr} 是<b>边缘节点的公网 IP</b>，
     * 不属于私网段 → {@link #isTrustedProxy} 返回 false → XFF 分支永不进入 →
     * 客户端真实 IP 解析失效，且 remoteAddr 会在边缘节点间漂移。
     * 后果：按 IP 的注册限流（5 次/小时）与登录锁定形同虚设
     * （2026-09-23 生产实测：注册限流连测 9 次都不触发）。
     *
     * <p>由于平台边缘 IP 段可能变动且不公开，这里做成<b>可配置</b>而非写死：
     * 通过 {@code app.security.trusted-proxy-prefixes} 注入（逗号分隔，前缀匹配）。
     * 默认留空，即保持原有的「仅私网」保守策略——不会因为这次改动而放宽信任边界。
     */
    private static volatile java.util.List<String> extraTrustedPrefixes = java.util.List.of();

    /** 由配置在启动时注入额外受信代理前缀；null/空白表示不额外信任任何前缀 */
    public static void setExtraTrustedPrefixes(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            extraTrustedPrefixes = java.util.List.of();
            return;
        }
        extraTrustedPrefixes = java.util.Arrays.stream(commaSeparated.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** 仅供测试观察当前生效的额外受信前缀 */
    static java.util.List<String> extraTrustedPrefixes() {
        return extraTrustedPrefixes;
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

    /** 判断是否为受信代理（内网/回环地址，或配置声明的平台代理前缀） */
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
        // 配置声明的平台代理前缀（如 Render 边缘节点公网段）
        for (String prefix : extraTrustedPrefixes) {
            if (ip.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
