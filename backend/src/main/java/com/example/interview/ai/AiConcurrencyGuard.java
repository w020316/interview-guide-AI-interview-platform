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

    /**
     * 后台任务专用许可（v1.34.1，P3-6 配额分区）。
     *
     * <p><b>解决的问题</b>：知识自动补充、岗位批量分类这类**非用户前台**的 AI 调用此前与
     * 用户请求共用同一批 5 个许可。它们一旦批量运行，会把许可占满并让用户的出题/评分请求
     * 排队等待，甚至触发排队超时——即「用户为后台任务买单」。
     *
     * <p>现将后台调用隔离到独立许可池（默认 2）。前台与后台**互不占用**对方的许可：
     * 后台打满时用户请求仍可用满 5 个前台许可；用户高峰时后台任务排队等待或放弃，不影响体验。
     * 系统对外总并发有界（5 + 2 = 7），不会放大上游压力。
     */
    private static final Semaphore BACKGROUND_SEMAPHORE = new Semaphore(2);

    /**
     * 排队获取许可的超时秒数。
     *
     * <p>优先级：Spring 配置 {@code app.ai.guard-acquire-timeout-seconds}
     * （由 {@code AiGuardConfig} 在启动时注入） &gt; 系统属性同名键 &gt; 默认 30。
     *
     * <p>v1.34.1（P3-8）：此前**只能**通过系统属性覆盖（`-Dapp.ai.guard-acquire-timeout-seconds=...`），
     * 无法在 application.yml 中配置，运维在容器化部署里不便调整。现改为可配置项。
     */
    private static volatile long acquireTimeoutSeconds =
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
     * 供 Spring 启动时注入配置值（v1.34.1，P3-8）。非正值忽略，保持既有默认/系统属性值。
     */
    public static void setAcquireTimeoutSeconds(long seconds) {
        if (seconds > 0) {
            acquireTimeoutSeconds = seconds;
        }
    }

    /** 当前生效的排队超时秒数（运维排查/测试观察用） */
    public static long acquireTimeoutSeconds() {
        return acquireTimeoutSeconds;
    }

    /**
     * 在许可保护下执行 AI 调用；排队超过 {@link #acquireTimeoutSeconds} 秒快速失败，
     * 中断时恢复中断标志并抛出 {@link AiGateTimeoutException}
     */
    public static <T> T call(Supplier<T> aiCall) {
        return call(acquireTimeoutSeconds, aiCall);
    }

    /**
     * 带显式排队超时的执行入口（测试与特殊场景使用）
     *
     * @throws AiGateTimeoutException 排队超时或等待被中断——调用方按"AI 暂不可用"处理，不应立即重试。
     *   本类型继承 {@link IllegalStateException}（保持既有「跳过重试」控制流），
     *   但由全局异常处理器优先匹配并映射为 503，而非 500。
     */
    public static <T> T call(long timeoutSeconds, Supplier<T> aiCall) {
        boolean acquired;
        try {
            acquired = SEMAPHORE.tryAcquire(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiGateTimeoutException("AI 调用等待许可时被中断", e);
        }
        if (!acquired) {
            throw new AiGateTimeoutException(
                    "AI 并发闸门排队超时（>" + timeoutSeconds + "s），请稍后重试");
        }
        try {
            return aiCall.get();
        } finally {
            SEMAPHORE.release();
        }
    }

    /** 仅供测试观察许可回收 */
    public static int availablePermits() {
        return SEMAPHORE.availablePermits();
    }

    /** 仅供测试观察后台许可回收 */
    public static int availableBackgroundPermits() {
        return BACKGROUND_SEMAPHORE.availablePermits();
    }

    /**
     * 后台 AI 调用入口（v1.34.1，P3-6）：使用独立的 2 个许可，与用户前台请求互不挤占。
     *
     * <p>适用于知识自动补充、岗位批量字段分类等**由系统触发、无用户在等**的调用。
     * 排队超时语义与 {@link #call(Supplier)} 一致（抛 {@link AiGateTimeoutException}），
     * 调用方应按「本次跳过、后续重试」处理，不得重试放大。
     */
    public static <T> T callBackground(Supplier<T> aiCall) {
        boolean acquired;
        try {
            acquired = BACKGROUND_SEMAPHORE.tryAcquire(acquireTimeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiGateTimeoutException("后台 AI 调用等待许可时被中断", e);
        }
        if (!acquired) {
            throw new AiGateTimeoutException(
                    "后台 AI 任务排队超时（>" + acquireTimeoutSeconds + "s），本次跳过");
        }
        try {
            return aiCall.get();
        } finally {
            BACKGROUND_SEMAPHORE.release();
        }
    }
}
