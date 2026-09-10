package com.example.interview.ai;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * AI 调用全局并发闸门（进程级共享）
 *
 * v1.23.1 新增（P2）：此前 generateQuestions 有信号量保护，但 evaluateAnswer、
 * answerWithRag、analyze 等其余 AI 调用未纳入，免费模型限流窗口下并发压力被放大。
 * 统一收敛到本闸门，所有同步 AI 调用共享 5 个许可。
 *
 * 用法：AiConcurrencyGuard.call(() -> chatClient.prompt()...call().content())
 */
public final class AiConcurrencyGuard {

    /** 与原 InterviewService.AI_SEMAPHORE 一致的并发上限 */
    private static final Semaphore SEMAPHORE = new Semaphore(5);

    private AiConcurrencyGuard() {
    }

    /**
     * 在许可保护下执行 AI 调用；中断时恢复中断标志并抛出 IllegalStateException
     */
    public static <T> T call(Supplier<T> aiCall) {
        try {
            SEMAPHORE.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI 调用等待许可时被中断", e);
        }
        try {
            return aiCall.get();
        } finally {
            SEMAPHORE.release();
        }
    }
}
