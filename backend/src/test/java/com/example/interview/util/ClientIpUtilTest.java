package com.example.interview.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 客户端 IP 解析工具单测
 *
 * <p>覆盖 BE-04 安全修复语义：从 XFF 右端向左取第一个非受信代理 IP；
 * 直连 IP 非受信代理时忽略 XFF；全部条目为受信代理时回退 remoteAddr。
 */
@DisplayName("客户端 IP 解析测试")
class ClientIpUtilTest {

    private static String resolve(String remoteAddr, String xff) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        if (xff != null) {
            request.addHeader("X-Forwarded-For", xff);
        }
        return ClientIpUtil.resolve(request);
    }

    @Test
    @DisplayName("无 XFF 头/空白 XFF：直接返回 remoteAddr")
    void noForwardedHeader() {
        assertThat(resolve("203.0.113.5", null)).isEqualTo("203.0.113.5");
        assertThat(resolve("203.0.113.5", "   ")).isEqualTo("203.0.113.5");
    }

    @Test
    @DisplayName("直连 IP 非受信代理：忽略 XFF 防伪造，返回 remoteAddr")
    void directConnectionNotTrusted_ignoresXff() {
        // 公网直连（非内网），XFF 任意伪造均不可信
        assertThat(resolve("203.0.113.5", "1.2.3.4, 5.6.7.8")).isEqualTo("203.0.113.5");
    }

    @Test
    @DisplayName("受信代理直连：从 XFF 右端取第一个非受信 IP")
    void trustedProxy_takesRightmostUntrusted() {
        // 逐级追加：客户端在最左
        assertThat(resolve("10.0.0.2", "198.51.100.7, 10.0.0.3"))
                .isEqualTo("198.51.100.7");
        // 多级代理：右侧内网跳过，取公网客户端
        assertThat(resolve("192.168.1.1", "198.51.100.7, 10.0.0.3, 192.168.0.9"))
                .isEqualTo("198.51.100.7");
    }

    @Test
    @DisplayName("XFF 单条目且非受信：直接返回该条目")
    void singleEntry_untrusted() {
        assertThat(resolve("127.0.0.1", "198.51.100.99")).isEqualTo("198.51.100.99");
    }

    @Test
    @DisplayName("XFF 全部为受信代理：回退 remoteAddr")
    void allTrusted_fallsBackToRemoteAddr() {
        assertThat(resolve("10.0.0.1", "10.0.0.2, 192.168.0.3, 127.0.0.1"))
                .isEqualTo("10.0.0.1");
    }

    @Test
    @DisplayName("XFF 条目含空白与空段：跳过空段取有效 IP")
    void blankEntries_skipped() {
        assertThat(resolve("127.0.0.1", " , 198.51.100.8 , 10.0.0.1"))
                .isEqualTo("198.51.100.8");
        // 最左为空段且右端受信：取中间有效段
        assertThat(resolve("127.0.0.1", "  , 203.0.113.10, 10.0.0.5"))
                .isEqualTo("203.0.113.10");
    }

    // ── isTrustedProxy 边界 ──

    @Test
    @DisplayName("isTrustedProxy: 内网/回环/IPv6 本机识别，公网不识别")
    void trustedProxyClassification() {
        assertThat(ClientIpUtil.isTrustedProxy("10.0.0.1")).isTrue();
        assertThat(ClientIpUtil.isTrustedProxy("192.168.1.1")).isTrue();
        assertThat(ClientIpUtil.isTrustedProxy("127.0.0.1")).isTrue();
        assertThat(ClientIpUtil.isTrustedProxy("::1ffff")).isTrue(); // startsWith("::1") 前缀匹配
        assertThat(ClientIpUtil.isTrustedProxy("172.16.0.1")).isTrue();
        assertThat(ClientIpUtil.isTrustedProxy("172.31.255.255")).isTrue();
        // 172.16.0.0/12 精确边界：15 与 32 不属于该网段
        assertThat(ClientIpUtil.isTrustedProxy("172.15.0.1")).isFalse();
        assertThat(ClientIpUtil.isTrustedProxy("172.32.0.1")).isFalse();
        // 非法 172 段（第二段非数字）不视为受信
        assertThat(ClientIpUtil.isTrustedProxy("172.abc.0.1")).isFalse();
        assertThat(ClientIpUtil.isTrustedProxy("203.0.113.1")).isFalse();
        assertThat(ClientIpUtil.isTrustedProxy(null)).isFalse();
    }
}
