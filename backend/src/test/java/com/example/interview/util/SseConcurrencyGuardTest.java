package com.example.interview.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link SseConcurrencyGuard} / {@link PerUserConcurrencyLimiter} 单元测试
 * （P1-01/P1-02：SSE 全局 + 每用户双层并发保护）
 */
class SseConcurrencyGuardTest {

    @Test
    @DisplayName("正常获取与释放：活跃计数随之增减")
    void acquireAndRelease() {
        SseConcurrencyGuard guard = new SseConcurrencyGuard(2, 1);
        assertEquals(SseConcurrencyGuard.Result.ACQUIRED, guard.tryAcquire("u1"));
        assertEquals(1, guard.activeCount());
        guard.release("u1");
        assertEquals(0, guard.activeCount());
    }

    @Test
    @DisplayName("同一用户超过每用户上限返回 USER_LIMIT，且不泄漏全局槽位")
    void perUserLimit() {
        SseConcurrencyGuard guard = new SseConcurrencyGuard(2, 1);
        assertEquals(SseConcurrencyGuard.Result.ACQUIRED, guard.tryAcquire("u1"));
        assertEquals(SseConcurrencyGuard.Result.USER_LIMIT, guard.tryAcquire("u1"));
        // 全局槽位未被 USER_LIMIT 路径泄漏：其他用户仍可获取
        assertEquals(SseConcurrencyGuard.Result.ACQUIRED, guard.tryAcquire("u2"));
        assertEquals(2, guard.activeCount());
    }

    @Test
    @DisplayName("不同用户占满全局上限后返回 GLOBAL_LIMIT，释放后可再次获取")
    void globalLimit() {
        SseConcurrencyGuard guard = new SseConcurrencyGuard(2, 1);
        assertEquals(SseConcurrencyGuard.Result.ACQUIRED, guard.tryAcquire("u1"));
        assertEquals(SseConcurrencyGuard.Result.ACQUIRED, guard.tryAcquire("u2"));
        assertEquals(SseConcurrencyGuard.Result.GLOBAL_LIMIT, guard.tryAcquire("u3"));
        guard.release("u1");
        assertEquals(SseConcurrencyGuard.Result.ACQUIRED, guard.tryAcquire("u3"));
    }

    @Test
    @DisplayName("每用户上限大于 1 时，同用户可持有多个槽位直至上限")
    void perUserLimitAboveOne() {
        SseConcurrencyGuard guard = new SseConcurrencyGuard(5, 2);
        assertEquals(SseConcurrencyGuard.Result.ACQUIRED, guard.tryAcquire("u1"));
        assertEquals(SseConcurrencyGuard.Result.ACQUIRED, guard.tryAcquire("u1"));
        assertEquals(SseConcurrencyGuard.Result.USER_LIMIT, guard.tryAcquire("u1"));
    }

    @Test
    @DisplayName("PerUserConcurrencyLimiter：计数归零后移除条目，不随历史用户无界增长")
    void limiterRemovesEmptyEntries() {
        PerUserConcurrencyLimiter limiter = new PerUserConcurrencyLimiter(1);
        limiter.tryAcquire("u1");
        limiter.tryAcquire("u2");
        limiter.release("u1");
        limiter.release("u2");
        assertEquals(0, limiter.activeUsers());
        // 释放后可重新获取
        assertEquals(true, limiter.tryAcquire("u1"));
    }
}
