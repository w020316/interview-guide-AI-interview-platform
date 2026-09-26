package com.example.interview.config;

import java.util.Arrays;

/**
 * AI 调用耗时窗口 —— 进程内自算百分位。
 *
 * <h2>为什么不用 Micrometer 的客户端百分位</h2>
 *
 * <p>线上 {@code ai.call.duration} 的 P95 长期恒为 0，而同一响应里 {@code totalCalls} 与
 * {@code avgMs} 都正常（2026-09-26 实测：{@code totalCalls=2, avgMs=8659.77, p95Ms=0.0}，
 * 且「调用后立即查询」已排除滑动窗口过期因素）。
 *
 * <p>为定位根因做了 8 组对照实验（见 {@code PercentileProbeTest} / {@code PercentileSpringProbeTest}），
 * 在进程内**全部返回正确的 P95（10196.4ms）**：
 *
 * <ol>
 *   <li>SimpleMeterRegistry + publishPercentiles + min/max expected（线上现状配置）</li>
 *   <li>仅 publishPercentiles</li>
 *   <li>+ publishPercentileHistogram + SLO 边界</li>
 *   <li>+ 显式滑动窗口（10min / 3 buffer）</li>
 *   <li>PrometheusMeterRegistry（生产同款注册表）</li>
 *   <li>Prometheus 注册表 + {@code find()} 取回（与 AdminService 取值方式一致）</li>
 *   <li>CompositeMeterRegistry（Spring Boot 会创建 @Primary 组合注册表）</li>
 *   <li>放进去真实 Spring 容器装配后注入（含 {@code management.metrics.tags.application} 公共 tag）</li>
 * </ol>
 *
 * <p>即：**配置写法、注册表类型、取值方式、Spring 装配全部正确**，根因未能在进程内复现。
 *
 * <p>与其继续依赖一个无法解释的库行为，不如自己算：本类用定长环形缓冲保存最近 N 次耗时，
 * 取百分位时排序后按位取。行为**完全可单测、可解释**，也不再受 Micrometer 版本语义影响。
 *
 * <p>注意：无样本时返回 {@code null} 而不是 0 —— 让调用方渲染「—」。
 * 同一页面里「缓存命中率」无数据时就显示「—」，P95 显示「0」会被读成「耗时极快」。
 */
public class AiLatencyWindow {

    /** 保留最近多少次调用。200 次足以刻画长尾，内存占用可忽略（200 × 8B）。 */
    public static final int CAPACITY = 200;

    private final long[] samples = new long[CAPACITY];
    /** 有效样本数（不超过 CAPACITY） */
    private int size = 0;
    private int cursor = 0;

    /** 记录一次耗时（毫秒）。负值忽略（与 Micrometer 对负值的行为保持一致）。 */
    public synchronized void recordMillis(long millis) {
        if (millis < 0) {
            return;
        }
        samples[cursor] = millis;
        cursor = (cursor + 1) % CAPACITY;
        if (size < CAPACITY) {
            size++;
        }
    }

    /** 记录一次耗时（纳秒），供与 {@code Timer.record(nanos, NANOSECONDS)} 并列调用。 */
    public synchronized void recordNanos(long nanos) {
        if (nanos < 0) {
            return;
        }
        recordMillis(nanos / 1_000_000L);
    }

    /** 当前窗口内的有效样本数 */
    public synchronized int size() {
        return size;
    }

    /** P95（毫秒）；无样本时返回 {@code null} */
    public synchronized Double p95() {
        return percentile(0.95);
    }

    /**
     * 任意百分位（毫秒）；无样本时返回 {@code null}。
     *
     * <p>取值方式：升序排序后取 {@code ceil(p × n) - 1} 位（最近秩法），
     * 与 Micrometer 客户端百分位的取法一致，便于与历史数字对照。
     */
    public synchronized Double percentile(double p) {
        if (size == 0) {
            return null;
        }
        double bounded = Math.max(0.0, Math.min(1.0, p));
        long[] sorted = Arrays.copyOf(samples, size);
        Arrays.sort(sorted);
        int idx = (int) Math.ceil(bounded * size) - 1;
        if (idx < 0) {
            idx = 0;
        }
        if (idx >= size) {
            idx = size - 1;
        }
        return (double) sorted[idx];
    }

    /** 清空窗口（仅测试与运维排查使用） */
    public synchronized void reset() {
        size = 0;
        cursor = 0;
    }
}
