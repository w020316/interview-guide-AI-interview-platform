package com.example.interview.util;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.Deque;

/**
 * 按用户键的滑动窗口限流器（进程内，无外部依赖）
 *
 * <p>用于对高成本接口（第三方抓取 / AI 生成）做按用户限流，防自动化刷额度。
 * 滑动窗口窗口统计请求数；内置陈旧键清理，防止 {@code Map} 无界增长导致内存泄漏
 * （对齐 AuthController 登录失败计数泄漏的修复经验）。
 *
 * <p>线程安全：单键队列操作在 {@code synchronized} 内完成，避免并发竞态。
 */
public final class PerUserRateLimiter {

    /** 单键在窗口内的请求队列；json-attach：{@code ConcurrentLinkedDeque} 存放时间戳 */
    private static final class Bucket {
        final Deque<Long> hits = new ConcurrentLinkedDeque<>();
        volatile long lastAccess = System.currentTimeMillis();
    }

    private static final int MAX_KEYS = 10_000;

    private final int maxRequests;
    private final long windowMillis;
    /** 达到键数上限后触发的清理阈值倍数（2 倍窗口） */
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public PerUserRateLimiter(int maxRequests, long windowMillis) {
        if (maxRequests <= 0 || windowMillis <= 0) {
            throw new IllegalArgumentException("限流参数必须为正");
        }
        this.maxRequests = maxRequests;
        this.windowMillis = windowMillis;
    }

    /**
     * 尝试放行一次请求。
     *
     * @param key 限流键（如 userId）
     * @return true=放行；false=超过当前窗口配额
     */
    public boolean allow(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (buckets.size() >= MAX_KEYS) {
            evictStale(now);
        }
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket());
        synchronized (bucket) {
            bucket.lastAccess = now;
            // 移除窗口已过期的旧请求
            while (!bucket.hits.isEmpty() && now - bucket.hits.peekFirst() > windowMillis) {
                bucket.hits.pollFirst();
            }
            if (bucket.hits.size() >= maxRequests) {
                return false;
            }
            bucket.hits.addLast(now);
            return true;
        }
    }

    /** 清理所有超过 2 倍窗口未触发的陈旧键，防止内存泄漏 */
    private synchronized void evictStale(long now) {
        long staleThreshold = now - windowMillis * 2;
        Iterator<Map.Entry<String, Bucket>> it = buckets.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Bucket> e = it.next();
            if (e.getValue().lastAccess < staleThreshold) {
                it.remove();
            }
        }
    }

    /** 窗口内当前剩余配额（仅用于日志/展示，非精确） */
    public int remaining(String key) {
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            return maxRequests;
        }
        synchronized (bucket) {
            return Math.max(0, maxRequests - bucket.hits.size());
        }
    }
}