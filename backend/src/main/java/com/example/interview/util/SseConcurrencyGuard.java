package com.example.interview.util;

import java.util.concurrent.Semaphore;

/**
 * SSE 流式连接双层并发保护：全局信号量 + 每用户上限
 *
 * v1.33.0 新增（P1-01/P1-02）：InterviewController 与 AgentService 此前各自维护
 * 全局信号量（20 许可）但无每用户限制，单个用户可占满全部槽位造成全站流式功能拒绝服务。
 * 统一收敛到本类，区分失败原因以便向用户给出准确提示。
 *
 * 释放约定与既有实现一致：仅在 emitter 的 onCompletion 回调中调用
 * {@link #release(String)}（onError/onTimeout 路径随后也会触发 onCompletion，
 * 避免双重释放导致可用许可只增不减）。
 */
public final class SseConcurrencyGuard {

    /** 获取结果：成功 / 达到全局上限 / 达到该用户上限 */
    public enum Result {
        ACQUIRED, GLOBAL_LIMIT, USER_LIMIT
    }

    private final Semaphore global;
    private final PerUserConcurrencyLimiter perUser;
    private final int globalMax;

    public SseConcurrencyGuard(int globalMax, int maxPerUser) {
        this.globalMax = Math.max(1, globalMax);
        this.global = new Semaphore(this.globalMax, true);
        this.perUser = new PerUserConcurrencyLimiter(maxPerUser);
    }

    /**
     * 尝试同时获取全局与用户槽位；任一失败则不占用任何资源（原子语义由实现保证：
     * 先全局后用户，用户失败时回滚全局）
     */
    public Result tryAcquire(String userId) {
        if (!global.tryAcquire()) {
            return Result.GLOBAL_LIMIT;
        }
        if (!perUser.tryAcquire(userId)) {
            global.release();
            return Result.USER_LIMIT;
        }
        return Result.ACQUIRED;
    }

    /** 归还全局与用户槽位（与 tryAcquire 成对调用，通常置于 onCompletion） */
    public void release(String userId) {
        perUser.release(userId);
        global.release();
    }

    /** 当前活跃连接数（观测用） */
    public int activeCount() {
        return globalMax - global.availablePermits();
    }

    /** 全局上限（观测用） */
    public int globalMax() {
        return globalMax;
    }
}
