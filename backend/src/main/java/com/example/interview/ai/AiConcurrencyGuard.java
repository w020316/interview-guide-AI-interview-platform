package com.example.interview.ai;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * AI 调用全局并发闸门（进程级共享）
 *
 * v1.23.1 新增（P2）：此前 generateQuestions 有信号量保护，但 evaluateAnswer、
 * answerWithRag、analyze 等其余 AI 调用未纳入，免费模型限流窗口下并发压力被放大。
 * 统一收敛到本闸门，所有同步 AI 调用共享 5 个许可。
 *
 * v1.33.0（P1-03）：acquire 改为带超时的 tryAcquire。此前无限等待且许可持有时长取决于
 * AI 调用本身——一旦 AI 网关挂起，5 个许可被永久占住，全站 AI 功能排队瘫痪只能重启恢复。
 * 配合 AiConfig 为同步调用配置的读超时（读超时 > 本排队超时），许可必然在有限时间内释放。
 *
 * 用法：AiConcurrencyGuard.call(() -> chatClient.prompt()...call().content())
 */
public final class AiConcurrencyGuard {

    /** 与原 InterviewService.AI_SEMAPHORE 一致的并发上限 */
    private static final Semaphore SEMAPHORE = new Semaphore(5);

    /** 排队获取许可的超时秒数；可通过系统属性 app.ai.guard-acquire-timeout-seconds 覆盖 */
    static final long ACQUIRE_TIMEOUT_SECONDS =
            parseTimeout(System.getProperty("app.ai.guard-acquire-timeout-seconds", "30"));

    private AiConcurrencyGuard() {
    }

    private static long parseTimeout(String raw) {
        try {
            long v = Long.parseLong(raw);
            return v > 0 ? v : 30;
        } catch (NumberFormatException e) {
            return 30;
        }
    }

    /**
     * 在许可保护下执行 AI 调用；排队超过 {@link #ACQUIRE_TIMEOUT_SECONDS} 秒快速失败，
     * 中断时恢复中断标志并抛出 IllegalStateException
     */
    public static <T> T call(Supplier<T> aiCall) {
        return call(ACQUIRE_TIMEOUT_SECONDS, aiCall);
    }

    /**
     * 带显式排队超时的执行入口（测试与特殊场景使用）
     *
     * @throws IllegalStateException 排队超时或等待被中断——调用方按"AI 暂不可用"处理，不应立即重试
     */
    public static <T> T call(long acquireTimeoutSeconds, Supplier<T> aiCall) {
        boolean acquired;
        try {
            acquired = SEMAPHORE.tryAcquire(acquireTimeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI 调用等待许可时被中断", e);
        }
        if (!acquired) {
            throw new IllegalStateException("AI 并发闸门排队超时（>" + acquireTimeoutSeconds + "s），请稍后重试");
        }
        try {
            return aiCall.get();
        } finally {
            SEMAPHORE.release();
        }
    }

    /** 仅供测试观察许可回收 */
    static int availablePermits() {
        return SEMAPHORE.availablePermits();
    }
}
