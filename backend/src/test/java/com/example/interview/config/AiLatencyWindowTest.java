package com.example.interview.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AiLatencyWindow} + {@link LatencyAwareTimer} 的回归测试。
 *
 * <p>这一组测试的存在意义：**「P95 恒为 0」这个缺陷已连续两轮出现**（第二轮记为已修、
 * 第三轮实测仍在）。两次修复的共同点是「改代码 + 人工看一眼线上数字」，没有测试锁定。
 * 本组测试就是那个锁定：
 *
 * <ul>
 *   <li>写入 3 个**已知**耗时（100 / 1000 / 10000 ms），断言 P95 == 10000 —— 任何让它退化为 0 的改动都会失败；</li>
 *   <li>断言「无样本」时返回 {@code null} 而不是 0 —— 前端据此渲染「—」，避免被读成「耗时极快」。</li>
 * </ul>
 */
@DisplayName("AiLatencyWindow / LatencyAwareTimer")
class AiLatencyWindowTest {

    private static final long MS = 1_000_000L;

    @Nested
    @DisplayName("百分位计算")
    class Percentile {

        @Test
        @DisplayName("三个已知样本的 P95 必须落在最大值上，不能为 0")
        void p95OfKnownSamples() {
            AiLatencyWindow w = new AiLatencyWindow();
            w.recordMillis(100);
            w.recordMillis(1000);
            w.recordMillis(10000);

            // ceil(0.95 × 3) - 1 = 2 → 排序后第 3 个 = 10000
            assertThat(w.p95()).isEqualTo(10000.0);
            assertThat(w.p95()).isGreaterThan(0.0);
            assertThat(w.percentile(0.5)).isEqualTo(1000.0);
        }

        @Test
        @DisplayName("无样本时返回 null（前端渲染「—」），而不是 0")
        void emptyReturnsNull() {
            assertThat(new AiLatencyWindow().p95()).isNull();
            assertThat(new AiLatencyWindow().percentile(0.5)).isNull();
        }

        @Test
        @DisplayName("单个样本：任何百分位都是它自己")
        void singleSample() {
            AiLatencyWindow w = new AiLatencyWindow();
            w.recordMillis(42);
            assertThat(w.p95()).isEqualTo(42.0);
            assertThat(w.percentile(0.01)).isEqualTo(42.0);
        }

        @Test
        @DisplayName("负值被忽略（与 Micrometer 对负值的行为一致）")
        void negativeIgnored() {
            AiLatencyWindow w = new AiLatencyWindow();
            w.recordMillis(-5);
            assertThat(w.size()).isZero();
            assertThat(w.p95()).isNull();
        }

        @Test
        @DisplayName("纳秒入口按毫秒记录，且大值不溢出")
        void nanosEntry() {
            AiLatencyWindow w = new AiLatencyWindow();
            w.recordNanos(71_000 * MS); // 71 秒
            assertThat(w.p95()).isEqualTo(71_000.0);
        }

        @Test
        @DisplayName("超过容量后只保留最近 CAPACITY 次")
        void ringBufferKeepsRecent() {
            AiLatencyWindow w = new AiLatencyWindow();
            w.recordMillis(999_999); // 这条会被挤出窗口
            for (int i = 0; i < AiLatencyWindow.CAPACITY; i++) {
                w.recordMillis(10);
            }
            assertThat(w.size()).isEqualTo(AiLatencyWindow.CAPACITY);
            assertThat(w.p95()).isEqualTo(10.0);
        }
    }

    @Nested
    @DisplayName("LatencyAwareTimer：注入 Timer 即自动进入窗口")
    class Decorator {

        private Timer buildWindowed(AiLatencyWindow window) {
            MeterRegistry registry = new SimpleMeterRegistry();
            Timer registered = Timer.builder("test.ai.duration")
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .minimumExpectedValue(Duration.ofMillis(100))
                    .maximumExpectedValue(Duration.ofMinutes(3))
                    .register(registry);
            return new LatencyAwareTimer(registered, window);
        }

        @Test
        @DisplayName("record(nanos) 同时写进 Micrometer 与窗口")
        void recordFeedsBoth() {
            AiLatencyWindow w = new AiLatencyWindow();
            Timer t = buildWindowed(w);

            t.record(100 * MS, TimeUnit.NANOSECONDS);
            t.record(1000 * MS, TimeUnit.NANOSECONDS);
            t.record(10000 * MS, TimeUnit.NANOSECONDS);

            assertThat(t.count()).isEqualTo(3);           // Micrometer 侧
            assertThat(t.mean(TimeUnit.MILLISECONDS)).isEqualTo(3700.0);
            assertThat(w.size()).isEqualTo(3);            // 窗口侧
            assertThat(w.p95()).isEqualTo(10000.0);       // ★ 锁定：不能是 0
        }

        @Test
        @DisplayName("record(Duration) 与 record(Supplier) 也进入窗口")
        void otherRecordOverloads() {
            AiLatencyWindow w = new AiLatencyWindow();
            Timer t = buildWindowed(w);

            t.record(Duration.ofMillis(500));
            String v = t.record(() -> "ok");

            assertThat(v).isEqualTo("ok");
            assertThat(w.size()).isEqualTo(2);
            // 第二个样本是「立即返回」的 supplier，耗时接近 0ms（整数毫秒取整后为 0），
            // 所以中位数确实是 0 —— 这里断言 P95 与最大值，别拿中位数当断言。
            assertThat(w.percentile(0.95)).isEqualTo(500.0);
            assertThat(t.max(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(500.0);
        }
    }
}
