package com.example.interview.ai;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 按厂商的 RPM 令牌桶（进程内、线程安全）。
 *
 * <h2>为什么需要（2026-10-03 第六轮实测）</h2>
 *
 * <p>此前的 {@link AiConcurrencyGuard} 只限制了「同时进行中的请求数」（Semaphore 5 + 2），
 * 完全**不限制每分钟请求数（RPM）**。实测：Agnes 免费档 20 RPM，5 并发 × 每次约 2s
 * 可轻易冲到约 150 RPM；30 并发突发时 14/30 成功、16 次 HTTP 429（且响应体为空，
 * 无法从 body 判断原因）。
 *
 * <p>本类与 Semaphore **叠加**（不是替换）：Semaphore 管「并发数」，本类管「每分钟总量」。
 * 两者是正交的——并发数达标不代表 RPM 达标（短请求、快返回时尤其明显）。
 *
 * <h2>算法</h2>
 *
 * <p>固定容量令牌桶：容量 = RPM，按 {@code RPM/60s} 的速率连续补充。桶满时允许
 * 一次突发（最多 RPM 个），之后以稳定速率放行，天然适配「窗口滑动后恢复」的语义。
 *
 * <h2>线程安全</h2>
 *
 * <p>每个 key 一个 {@link Bucket}，桶内以 {@code synchronized} 串行化「补充 + 扣减」，
 * 桶的创建用 {@link ConcurrentHashMap#computeIfAbsent} 保证只建一次。
 *
 * <h2>可测试性</h2>
 *
 * <p>时钟通过 {@link LongSupplier} 注入，单测可拨动时间验证「第 N+1 次被限流、窗口滑动后恢复」，
 * 无需真实等待 60 秒。
 */
public final class AiRateLimiter {

    /** 令牌桶窗口：1 分钟（RPM 的定义窗口） */
    private static final long WINDOW_MILLIS = 60_000L;

    /** key（厂商端点）→ 每分钟上限；未配置或 ≤0 表示不限制 */
    private final Map<String, Integer> rpmByKey;

    private final LongSupplier clockMillis;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public AiRateLimiter(Map<String, Integer> rpmByKey) {
        this(rpmByKey, System::currentTimeMillis);
    }

    public AiRateLimiter(Map<String, Integer> rpmByKey, LongSupplier clockMillis) {
        this.rpmByKey = rpmByKey == null ? Map.of() : new LinkedHashMap<>(rpmByKey);
        this.clockMillis = clockMillis == null ? System::currentTimeMillis : clockMillis;
    }

    /** 不限制任何 key 的实例（配置关闭 / 测试无策略时使用） */
    public static AiRateLimiter disabled() {
        return new AiRateLimiter(Map.of());
    }

    /** 是否配置了至少一个限流 key */
    public boolean isEnabled() {
        return !rpmByKey.isEmpty();
    }

    /**
     * 非阻塞地尝试取一个令牌。
     *
     * @param key 厂商端点标识；未配置该 key 或 RPM ≤ 0 视为不限制，恒返回 {@code true}
     * @return 取到令牌返回 true；桶已空返回 false（调用方应降级到下一节点，而不是阻塞等待）
     */
    public boolean tryAcquire(String key) {
        if (key == null) {
            return true;
        }
        Integer rpm = rpmByKey.get(key);
        if (rpm == null || rpm <= 0) {
            return true;
        }
        return buckets.computeIfAbsent(key, k -> new Bucket(rpm, clockMillis)).tryAcquire();
    }

    /** 该 key 配置的每分钟上限（未配置返回 0） */
    public int rpmOf(String key) {
        if (key == null) {
            return 0;
        }
        Integer rpm = rpmByKey.get(key);
        return rpm == null ? 0 : rpm;
    }

    /** 固定容量令牌桶：容量 = RPM，按 RPM/窗口 速率连续补充 */
    private static final class Bucket {

        private final double capacity;
        private final double refillPerMillis;
        private final LongSupplier clock;

        private double tokens;
        private long lastRefillMillis;

        Bucket(int rpm, LongSupplier clock) {
            this.capacity = rpm;
            this.refillPerMillis = rpm / (double) WINDOW_MILLIS;
            this.clock = clock;
            this.tokens = rpm; // 起始满桶：允许一次性突发到 RPM
            this.lastRefillMillis = clock.getAsLong();
        }

        synchronized boolean tryAcquire() {
            long now = clock.getAsLong();
            long elapsed = now - lastRefillMillis;
            if (elapsed > 0) {
                tokens = Math.min(capacity, tokens + elapsed * refillPerMillis);
                lastRefillMillis = now;
            }
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
