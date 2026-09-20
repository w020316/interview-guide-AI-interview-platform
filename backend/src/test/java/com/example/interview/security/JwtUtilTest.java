package com.example.interview.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理员角色签发/解析单元测试（v1.31.4）
 */
class JwtUtilTest {

    private static final String SECRET = "unit-test-secret-0123456789abcdef0123456789abcdef";

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(jwtUtil, "expirationMs", 600000L);
        ReflectionTestUtils.setField(jwtUtil, "adminUsernames", "root,sysadmin");
    }

    @Test
    @DisplayName("名单内用户签发 ROLE_ADMIN")
    void adminInList_getsRoleAdmin() {
        jwtUtil.validateSecret();
        String token = jwtUtil.generateToken("1", "root");
        assertEquals("ROLE_ADMIN", jwtUtil.extractRole(token));
    }

    @Test
    @DisplayName("普通用户签发 ROLE_USER")
    void normalUser_getsRoleUser() {
        jwtUtil.validateSecret();
        String token = jwtUtil.generateToken("2", "alice");
        assertEquals("ROLE_USER", jwtUtil.extractRole(token));
        assertEquals("2", jwtUtil.extractUserId(token));
    }

    @Test
    @DisplayName("jti：每次签发生成唯一 token 标识且可回读（登出黑名单依据）")
    void generateToken_jti_uniqueAndExtractable() {
        jwtUtil.validateSecret();
        String token1 = jwtUtil.generateToken("1", "root");
        String token2 = jwtUtil.generateToken("1", "root");

        String jti1 = jwtUtil.extractJti(token1);
        String jti2 = jwtUtil.extractJti(token2);

        assertTrue(jti1 != null && !jti1.isBlank());
        assertTrue(jti2 != null && !jti2.isBlank());
        assertFalse(jti1.equals(jti2)); // 同一用户两次签发 jti 也不同
    }

    @Test
    @DisplayName("extractExpirationMs：回读过期时间，约等于配置的有效期")
    void extractExpirationMs_roundtrip() {
        jwtUtil.validateSecret();
        long before = System.currentTimeMillis();
        String token = jwtUtil.generateToken("1", "root");
        long exp = jwtUtil.extractExpirationMs(token);
        // 有效期 600000ms，允许 1 秒时钟偏差
        assertTrue(exp >= before + 600_000L - 1_000L);
        assertTrue(exp <= System.currentTimeMillis() + 600_000L + 1_000L);
    }

    @Test
    @DisplayName("空白名单时任何人均为普通用户")
    void emptyAdminList_allNormal() {
        ReflectionTestUtils.setField(jwtUtil, "adminUsernames", "");
        jwtUtil.validateSecret();
        String token = jwtUtil.generateToken("3", "root");
        assertEquals("ROLE_USER", jwtUtil.extractRole(token));
    }

    @Test
    @DisplayName("isAdminUsername：名单命中为 true，未命中/空白名单/null 为 false")
    void isAdminUsername_matchesReservedList() {
        jwtUtil.validateSecret();
        assertTrue(jwtUtil.isAdminUsername("root"));
        assertFalse(jwtUtil.isAdminUsername("alice"));

        ReflectionTestUtils.setField(jwtUtil, "adminUsernames", "");
        jwtUtil.validateSecret();
        assertFalse(jwtUtil.isAdminUsername("root"));
        assertFalse(jwtUtil.isAdminUsername(null));
    }
}