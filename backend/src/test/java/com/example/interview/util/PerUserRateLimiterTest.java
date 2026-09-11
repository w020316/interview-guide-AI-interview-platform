package com.example.interview.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按用户限流器单元测试
 */
class PerUserRateLimiterTest {

    @Test
    @DisplayName("窗口内放行达到配额后拒绝")
    void allow_quota_enforced() {
        PerUserRateLimiter limiter = new PerUserRateLimiter(2, 60_000L);
        assertTrue(limiter.allow("u1"));
        assertTrue(limiter.allow("u1"));
        assertFalse(limiter.allow("u1"));
        // 其他用户不受影响
        assertTrue(limiter.allow("u2"));
    }

    @Test
    @DisplayName("空键/空实现兜底拒绝")
    void allow_blankKey_rejected() {
        PerUserRateLimiter limiter = new PerUserRateLimiter(5, 60_000L);
        assertFalse(limiter.allow(null));
        assertFalse(limiter.allow(""));
    }

    @Test
    @DisplayName("独立用户各自的配额独立")
    void allow_independentPerUser() {
        PerUserRateLimiter limiter = new PerUserRateLimiter(1, 60_000L);
        assertTrue(limiter.allow("alice"));
        assertFalse(limiter.allow("alice"));
        assertTrue(limiter.allow("bob"));
        assertFalse(limiter.allow("bob"));
    }
}