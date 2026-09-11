package com.example.interview.util;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * 服务端请求校验工具（防 SSRF）
 *
 * <p>用于"后端抓取用户传入 URL"或"把用户传入 URL 交给 AI 后端抓取"的场景（岗位/简历导入、多模态附图），
 * 统一校验：
 * <ol>
 *   <li>仅允许 http/https 协议</li>
 *   <li>必须包含主机名</li>
 *   <li>域名解析出的全部 IP 均须为公网地址（拒绝 loopback / 站点本地 / 链路本地 / 任意 / 组播）</li>
 *   <li>显式拒绝常见的云元数据 / 容器宿主机等敏感网段</li>
 * </ol>
 *
 * <p>说明：DNS 重绑定（校验时公网 IP、连接时换内网 IP）依赖"解析与连接分离"的 TOCTOU 窗口，
 * 本工具为最佳努力校验；彻底阻断需把连接也绑定到已校验的 IP 并强制 Host 头（会影响 HTTPS/证书 SAN 校验），
 * 故作为已记录残余风险，不做破坏性改动。
 */
public final class SsrUrlValidator {

    private SsrUrlValidator() {
    }

    /** 校验结果：失败时 message 为具体原因，成功时原样返回规范化后的 URL */
    public static final class Result {
        public final boolean ok;
        public final String message;
        public final String normalizedUrl;

        private Result(boolean ok, String message, String normalizedUrl) {
            this.ok = ok;
            this.message = message;
            this.normalizedUrl = normalizedUrl;
        }

        static Result pass(String url) {
            return new Result(true, null, url);
        }

        static Result fail(String reason) {
            return new Result(false, reason, null);
        }
    }

    /**
     * 校验 URL 是否允许服务端抓取；合法返回 {@link Result#ok()}=true。
     *
     * @param rawUrl 用户传入的原始 URL
     */
    public static Result validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return Result.fail("URL 不能为空");
        }
        String url = rawUrl.trim();
        String lower = url.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return Result.fail("请输入有效的 URL（以 http:// 或 https:// 开头）");
        }

        final URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return Result.fail("URL 格式不正确");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return Result.fail("URL 缺少主机名");
        }
        // 仅允许标准 Web 端口或空的（80/443 默认）；封禁敏感端口（如 6379 Redis、3306 MySQL 等非 HTTP 服务）
        int port = uri.getPort();
        if (port > 0 && port != 80 && port != 443) {
            return Result.fail("不支持访问该端口");
        }

        // 协议内网关键网段显式拦截（含 AWS/阿里云元数据、Docker 网关等）
        if (isSensitiveHost(host)) {
            return Result.fail("不支持访问内部地址");
        }

        // 解析全部 IP，任一非公网即拒绝
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses == null || addresses.length == 0) {
                return Result.fail("域名无法解析，请检查 URL");
            }
            for (InetAddress addr : addresses) {
                if (isBlocked(addr)) {
                    return Result.fail("不支持访问内网地址");
                }
            }
        } catch (UnknownHostException e) {
            return Result.fail("域名无法解析，请检查 URL");
        }

        return Result.pass(url);
    }

    /** 域名关键字白名单拦截：命中常见内网/元数据主机名关键字 */
    private static boolean isSensitiveHost(String host) {
        String h = host.toLowerCase();
        return h.equals("169.254.169.254")
                || h.equals("metadata.google.internal")
                || h.equals("metadata")
                || h.startsWith("metadata.")
                || h.endsWith(".internal")
                || h.endsWith(".local")
                || h.equals("localhost")
                || (!h.contains(".") && isNumericIp(h));
    }

    private static boolean isNumericIp(String host) {
        if (host == null || host.isEmpty()) return false;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c != '.' && (c < '0' || c > '9')) return false;
        }
        return true;
    }

    /** 内网/保留网段检测：单地址 + IPv4/IPv6 */
    private static boolean isBlocked(InetAddress addr) {
        if (addr == null) return false;
        return addr.isLoopbackAddress()
                || addr.isSiteLocalAddress()
                || addr.isLinkLocalAddress()
                || addr.isAnyLocalAddress()
                || addr.isMulticastAddress();
    }
}