package com.example.interview.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    @DisplayName("P1-3：照抄 .env.example 的占位密钥 → 启动失败（长度够也不能放行）")
    void validateSecret_rejectsExamplePlaceholder() {
        String example = "change-this-in-production-must-be-32-chars-min!!";
        assertTrue(example.getBytes(java.nio.charset.StandardCharsets.UTF_8).length >= 32,
                "前提：示例串本身长度达标，否则测不到占位校验");
        ReflectionTestUtils.setField(jwtUtil, "jwtSecret", example);

        IllegalStateException e = assertThrows(IllegalStateException.class, jwtUtil::validateSecret);
        assertTrue(e.getMessage().contains("占位值"), "报错要指出是占位值，而不是笼统的『不合法』：" + e.getMessage());
        assertTrue(e.getMessage().contains("openssl"), "报错要给可执行的修复动作：" + e.getMessage());
    }

    @Test
    @DisplayName("P1-3：changeme / placeholder 变体同样被拦")
    void validateSecret_rejectsPlaceholderVariants() {
        for (String bad : new String[]{
                "changeme-changeme-changeme-changeme-1234",
                "placeholder-secret-placeholder-secret-1234",
                "please-change-me-before-deploy-0123456789ab"}) {
            ReflectionTestUtils.setField(jwtUtil, "jwtSecret", bad);
            assertThrows(IllegalStateException.class, jwtUtil::validateSecret, "应拦截：" + bad);
        }
    }

    @Test
    @DisplayName("P1-3：全同字符属低熵，启动失败")
    void validateSecret_rejectsLowEntropy() {
        ReflectionTestUtils.setField(jwtUtil, "jwtSecret", "a".repeat(48));
        IllegalStateException e = assertThrows(IllegalStateException.class, jwtUtil::validateSecret);
        assertTrue(e.getMessage().contains("过于简单"), e.getMessage());
    }

    @Test
    @DisplayName("P1-3：含连续序列的真实密钥不被误杀（避免线上启动失败）")
    void validateSecret_acceptsSecretWithSequences() {
        // 含 16 位连续 16 进制片段，但整体是人工构造的强密钥 —— 必须放行。
        // 这条用例锁住「不做连续序列检测」这个决策，防止后续有人加回来把线上搞挂。
        ReflectionTestUtils.setField(jwtUtil, "jwtSecret",
                "unit-test-secret-0123456789abcdef0123456789abcdef");
        jwtUtil.validateSecret();
    }

    @Test
    @DisplayName("P1-3：真实随机密钥不被误杀（避免线上启动失败）")
    void validateSecret_acceptsRealisticSecret() {
        // 本地开发默认值：不含占位特征词、非低熵 —— 必须放行，否则本地与测试环境全挂
        ReflectionTestUtils.setField(jwtUtil, "jwtSecret", "local-dev-secret-key-must-be-32-chars-min!!");
        jwtUtil.validateSecret();

        // 随机 base64（openssl rand -base64 48 的形态）
        ReflectionTestUtils.setField(jwtUtil, "jwtSecret",
                "kR7vQ2mX9pLzT4wYbN6cJ1hF8sD3gA5eU0iOqRtZxCvBnM2lKjH");
        jwtUtil.validateSecret();
    }

    @Test
    @DisplayName("P1-3：长度不足仍先被长度校验拦下（不改变既有语义）")
    void validateSecret_stillRejectsShortSecret() {
        ReflectionTestUtils.setField(jwtUtil, "jwtSecret", "short");
        IllegalStateException e = assertThrows(IllegalStateException.class, jwtUtil::validateSecret);
        assertTrue(e.getMessage().contains("32"));
    }
}