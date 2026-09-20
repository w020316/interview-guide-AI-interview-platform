package com.example.interview.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TokenBlacklistService} 单元测试
 *
 * <p>回归防线（P1 2026-09-20）：登出即吊销。此前 JWT 无状态、登出仅清前端 token，
 * 被盗 token 在最长 24h 内无法失效。
 */
class TokenBlacklistServiceTest {

    private final TokenBlacklistService svc = new TokenBlacklistService();

    @Test
    @DisplayName("revoke 后 isRevoked 为 true")
    void revoke_makesTokenRevoked() {
        svc.revoke("jti-1", System.currentTimeMillis() + 60_000L);
        assertTrue(svc.isRevoked("jti-1"));
        assertEquals(1, svc.size());
    }

    @Test
    @DisplayName("未加入黑名单的 jti 不视为已吊销")
    void unknownJti_notRevoked() {
        assertFalse(svc.isRevoked("nope"));
    }

    @Test
    @DisplayName("null jti 安全返回 false（兼容旧版无 jti 的 token）")
    void nullJti_notRevoked() {
        assertFalse(svc.isRevoked(null));
    }

    @Test
    @DisplayName("已过期的 token 不入黑名单")
    void revoke_expiredToken_isIgnored() {
        svc.revoke("expired", System.currentTimeMillis() - 1_000L);
        assertFalse(svc.isRevoked("expired"));
        assertEquals(0, svc.size());
    }

    @Test
    @DisplayName("null jti 不入黑名单")
    void revoke_nullJti_isIgnored() {
        svc.revoke(null, System.currentTimeMillis() + 60_000L);
        assertEquals(0, svc.size());
    }

    @Test
    @DisplayName("条目过期后 isRevoked 顺带惰性清理并放行")
    void expiredEntry_lazyCleanup_onCheck() throws InterruptedException {
        svc.revoke("soon", System.currentTimeMillis() + 60L);
        assertTrue(svc.isRevoked("soon"));
        // 模拟时间流逝：条目过期后再次查询 → 放行且条目被清理
        Thread.sleep(120L);
        assertFalse(svc.isRevoked("soon"));
        assertEquals(0, svc.size());
    }
}
