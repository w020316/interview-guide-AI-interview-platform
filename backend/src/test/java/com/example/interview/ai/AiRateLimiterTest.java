package com.example.interview.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 按厂商 RPM 令牌桶单测（v1.48.0，第六轮 P1-01）。
 *
 * <p>覆盖：容量内放行、第 N+1 次被限流、窗口滑动后按速率恢复、未配置/0 视为不限、
 * 多线程下发放总数不超过容量。
 */
@DisplayName("按厂商 RPM 令牌桶")
class AiRateLimiterTest {

    /** 可拨动的假时钟，避免真实等待 60 秒 */
    private final AtomicLong clock = new AtomicLong(0);

    private AiRateLimiter limiter(int rpm) {
        Map<String, Integer> map = new HashMap<>();
        map.put("agnes", rpm);
        return new AiRateLimiter(map, clock::get);
    }

    @Test
    @DisplayName("容量内放行，第 N+1 次被限流；窗口滑动后按速率恢复")
    void blocksAfterCapacity_andRecoversAfterWindow() {
        AiRateLimiter limiter = limiter(3);

        // 起始满桶：连续 3 次放行
        assertThat(limiter.tryAcquire("agnes")).isTrue();
        assertThat(limiter.tryAcquire("agnes")).isTrue();
        assertThat(limiter.tryAcquire("agnes")).isTrue();
        // 第 4 次（N+1）被限流
        assertThat(limiter.tryAcquire("agnes")).as("第 N+1 次应被限流").isFalse();

        // 20s 后补充 3 * 20/60 = 1 个令牌
        clock.addAndGet(20_000);
        assertThat(limiter.tryAcquire("agnes")).as("窗口滑动 1/3 后应恢复 1 个令牌").isTrue();
        assertThat(limiter.tryAcquire("agnes")).as("只恢复了 1 个，随即再次被限流").isFalse();

        // 再满 60s：桶回满（容量 3）
        clock.addAndGet(60_000);
        assertThat(limiter.tryAcquire("agnes")).isTrue();
        assertThat(limiter.tryAcquire("agnes")).isTrue();
        assertThat(limiter.tryAcquire("agnes")).isTrue();
        assertThat(limiter.tryAcquire("agnes")).isFalse();
    }

    @Test
    @DisplayName("未配置的 key 与 rpm=0 均视为不限流")
    void unconfiguredOrZeroRpm_isUnlimited() {
        AiRateLimiter limiter = limiter(0);
        for (int i = 0; i < 100; i++) {
            assertThat(limiter.tryAcquire("agnes")).isTrue();
            assertThat(limiter.tryAcquire("nvidia")).as("未配置的 key 恒放行").isTrue();
        }
        assertThat(limiter.isEnabled()).isTrue();
        assertThat(AiRateLimiter.disabled().isEnabled()).isFalse();
        assertThat(AiRateLimiter.disabled().tryAcquire("agnes")).isTrue();
    }

    @Test
    @DisplayName("同一 key 共享一个桶；rpmOf 反映配置")
    void sharedBucketPerKey() {
        AiRateLimiter limiter = limiter(2);
        assertThat(limiter.rpmOf("agnes")).isEqualTo(2);
        assertThat(limiter.rpmOf("nvidia")).isZero();

        assertThat(limiter.tryAcquire("agnes")).isTrue();
        assertThat(limiter.tryAcquire("agnes")).isTrue();
        assertThat(limiter.tryAcquire("agnes")).isFalse();
        // 不同 key 互不影响
        assertThat(limiter.tryAcquire("nvidia")).isTrue();
    }

    @Test
    @DisplayName("并发下发放总数不超过容量（线程安全）")
    void concurrentAcquire_neverExceedsCapacity() throws Exception {
        int rpm = 100;
        AiRateLimiter limiter = limiter(rpm);
        int threads = 200;
        AtomicInteger granted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            CountDownLatch done = new CountDownLatch(threads);
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        if (limiter.tryAcquire("agnes")) {
                            granted.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        // 时钟不动 → 无补充，发放数必须恰好等于容量
        assertThat(granted.get()).isEqualTo(rpm);
    }
}
