package com.example.interview.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
    @DisplayName("空白名单时任何人均为普通用户")
    void emptyAdminList_allNormal() {
        ReflectionTestUtils.setField(jwtUtil, "adminUsernames", "");
        jwtUtil.validateSecret();
        String token = jwtUtil.generateToken("3", "root");
        assertEquals("ROLE_USER", jwtUtil.extractRole(token));
    }
}