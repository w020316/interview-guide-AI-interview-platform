package com.example.interview.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 按用户维度的并发槽位控制器（无锁实现）
 *
 * v1.33.0 新增（P1-01/P1-02）：此前 SSE 端点仅有全局并发上限，任一登录用户可用多个
 * 长连接占满全部全局槽位，拒绝所有其他用户的流式服务。本类提供每用户独立上限，
 * 与全局信号量配合形成双层并发保护。
 */
public final class PerUserConcurrencyLimiter {

    private final ConcurrentHashMap<String, AtomicInteger> active = new ConcurrentHashMap<>();
    private final int maxPerUser;

    public PerUserConcurrencyLimiter(int maxPerUser) {
        this.maxPerUser = Math.max(1, maxPerUser);
    }

    /**
     * 尝试为用户占用一个槽位；超过每用户上限返回 false
     */
    public boolean tryAcquire(String userId) {
        AtomicInteger n = active.computeIfAbsent(userId, k -> new AtomicInteger());
        for (; ; ) {
            int cur = n.get();
            if (cur >= maxPerUser) {
                return false;
            }
            if (n.compareAndSet(cur, cur + 1)) {
                return true;
            }
        }
    }

    /**
     * 归还用户槽位；计数归零后移除条目，避免 map 随历史用户无界增长
     */
    public void release(String userId) {
        active.computeIfPresent(userId, (k, n) -> n.decrementAndGet() <= 0 ? null : n);
    }

    /** 当前持有槽位的用户数（观测用） */
    public int activeUsers() {
        return active.size();
    }
}
