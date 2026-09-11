package com.example.interview.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSRF 校验器单元测试（确定性用例，不依赖真实网络）
 */
class SsrUrlValidatorTest {

    @Test
    @DisplayName("合法公网 URL 放行")
    void validate_publicUrl_pass() {
        // TEST-NET 公网 IP 字面量：无需 DNS
        assertTrue(SsrUrlValidator.validate("https://192.0.2.1/a.png").ok);
        assertTrue(SsrUrlValidator.validate("http://203.0.113.7/resume").ok);
    }

    @Test
    @DisplayName("空/缺失/非法协议被拒")
    void validate_invalidProtocol_rejected() {
        assertFalse(SsrUrlValidator.validate(null).ok);
        assertFalse(SsrUrlValidator.validate("").ok);
        assertFalse(SsrUrlValidator.validate("ftp://example.com/a").ok);
        assertFalse(SsrUrlValidator.validate("javascript:alert(1)").ok);
    }

    @Test
    @DisplayName("内网/回环/元数据网段被拒")
    void validate_internal_rejected() {
        assertFalse(SsrUrlValidator.validate("http://127.0.0.1/admin").ok);
        assertFalse(SsrUrlValidator.validate("http://localhost/admin").ok);
        assertFalse(SsrUrlValidator.validate("http://192.168.1.1/internal").ok);
        assertFalse(SsrUrlValidator.validate("http://10.0.0.1/x").ok);
        assertFalse(SsrUrlValidator.validate("http://169.254.169.254/latest/meta-data/").ok);
        assertFalse(SsrUrlValidator.validate("http://metadata.google.internal/computeMetadata").ok);
    }

    @Test
    @DisplayName("非标准/敏感端口被拒")
    void validate_sensitivePort_rejected() {
        assertFalse(SsrUrlValidator.validate("http://192.0.2.1:6379/").ok);
        assertFalse(SsrUrlValidator.validate("http://192.0.2.1:3306/").ok);
        assertTrue(SsrUrlValidator.validate("http://192.0.2.1:80/").ok);
    }
}