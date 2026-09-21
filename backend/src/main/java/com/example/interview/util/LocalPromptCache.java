package com.example.interview.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内提示词结果缓存（Redis 不可用时的兜底）
 *
 * <p><b>解决的问题（v1.34.1，P2-5）</b>：出题缓存（1h）与简历分析缓存（30min）此前**只**依赖
 * Redis，读写异常时代码仅打 WARN 后直连 AI。因此在未部署 Redis 的环境（本地/低配单机部署，
 * 本项目的 {@code local} profile 即如此）缓存**整体旁路**：
 * <ul>
 *   <li>同一份简历重复分析每次都全额调用 LLM —— 真机实测 {@code cache.hit.count = 0}、
 *       重复请求耗时 7.12s（与首次 9.24s 同量级，证明未命中）；</li>
 *   <li>同一岗位重复出题同样每次都打模型，成本与延迟翻倍。</li>
 * </ul>
 *
 * <p><b>设计取舍</b>
 * <ul>
 *   <li><b>Redis 优先，本缓存仅在 Redis 读写失败时启用</b>——语义是「Redis 故障降级」，
 *       而非第二层常规缓存。这样在 Redis 正常时行为与改造前完全一致，不会引入双写不一致。</li>
 *   <li><b>实例级而非静态</b>——每个 Service 实例持有自己的缓存，避免单测之间通过静态状态
 *       互相污染（{@code @InjectMocks} 每个用例新建实例）。</li>
 *   <li><b>有界 LRU + TTL</b>——防止无界增长；默认 500 条，条目数远小于内存向量库 500~2000。</li>
 *   <li>键已含 userId（调用方保证），不会跨用户串扰。</li>
 * </ul>
 *
 * <p>线程安全：内部用 {@code synchronizedMap} 包装访问序 LRU（LinkedHashMap accessOrder=true）。
 */
public final class LocalPromptCache {

    /** 单条缓存项：值与过期时间戳 */
    private record Entry(String value, long expireAtMillis) {
    }

    private final Map<String, Entry> store;

    /** 命中/未命中计数（供日志与测试观察） */
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public LocalPromptCache(int maxEntries) {
        int cap = maxEntries > 0 ? maxEntries : 500;
        // accessOrder = true 实现 LRU：get 会把条目移到尾部，超容量时淘汰最久未访问的头节点
        this.store = Collections.synchronizedMap(
                new LinkedHashMap<String, Entry>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                        return size() > cap;
                    }
                });
    }

    /**
     * 读取缓存。
     *
     * @return 命中的值；未命中或已过期返回 {@code null}（过期项顺手移除）
     */
    public String get(String key) {
        if (key == null) {
            return null;
        }
        Entry e = store.get(key);
        if (e == null) {
            misses.incrementAndGet();
            return null;
        }
        if (System.currentTimeMillis() > e.expireAtMillis()) {
            store.remove(key);
            misses.incrementAndGet();
            return null;
        }
        hits.incrementAndGet();
        return e.value();
    }

    /**
     * 写入缓存。
     *
     * @param ttlMillis 存活时长（毫秒）；非正值视为不缓存，直接忽略
     */
    public void put(String key, String value, long ttlMillis) {
        if (key == null || value == null || ttlMillis <= 0) {
            return;
        }
        store.put(key, new Entry(value, System.currentTimeMillis() + ttlMillis));
    }

    /** 当前条目数（测试观察用） */
    public int size() {
        return store.size();
    }

    /** 命中次数（测试观察用） */
    public long hitCount() {
        return hits.get();
    }

    /** 未命中次数（测试观察用） */
    public long missCount() {
        return misses.get();
    }

    /** 清空（测试用） */
    public void clear() {
        store.clear();
    }
}
