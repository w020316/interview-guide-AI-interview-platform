package com.example.interview.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AiConcurrencyGuard} 单元测试（P1-03：排队超时与许可回收）
 */
class AiConcurrencyGuardTest {

    @Test
    @DisplayName("正常执行后许可归还（回到 5/5）")
    void call_releasesPermitOnSuccess() {
        String result = AiConcurrencyGuard.call(1, () -> "ok");
        assertEquals("ok", result);
        assertEquals(5, AiConcurrencyGuard.availablePermits());
    }

    @Test
    @DisplayName("调用方异常后许可仍归还")
    void call_releasesPermitOnFailure() {
        assertThrows(RuntimeException.class,
                () -> AiConcurrencyGuard.call(1, () -> {
                    throw new RuntimeException("boom");
                }));
        assertEquals(5, AiConcurrencyGuard.availablePermits());
    }

    @Test
    @DisplayName("5 个许可被占满时排队超时快速失败；占位释放后许可全部回收")
    void call_timesOutWhenSaturated() throws Exception {
        CountDownLatch started = new CountDownLatch(5);
        CountDownLatch release = new CountDownLatch(1);
        List<Thread> holders = new ArrayList<>();
        try {
            for (int i = 0; i < 5; i++) {
                Thread t = Thread.ofVirtual().unstarted(() ->
                        AiConcurrencyGuard.call(() -> {
                            started.countDown();
                            try {
                                release.await(10, TimeUnit.SECONDS);
                            } catch (InterruptedException ignored) {
                                Thread.currentThread().interrupt();
                            }
                            return null;
                        }));
                holders.add(t);
                t.start();
            }
            assertTrue(started.await(5, TimeUnit.SECONDS), "5 个占位调用未全部进入闸门");

            long begin = System.nanoTime();
            assertThrows(IllegalStateException.class, () -> AiConcurrencyGuard.call(1, () -> "x"));
            long costMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
            assertTrue(costMs < 5000, "排队超时应快速失败，实际耗时 " + costMs + "ms");
        } finally {
            release.countDown();
            for (Thread t : holders) {
                t.join(5000);
            }
        }
        assertEquals(5, AiConcurrencyGuard.availablePermits());
    }
}
