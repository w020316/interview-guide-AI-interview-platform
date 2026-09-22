package com.example.interview.service.job;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 招聘数据源健康登记表（v1.38.0）
 *
 * <p><b>为什么需要它</b>：刷新流程此前只把失败写进日志——{@code log.warn("平台 X 岗位拉取失败")}。
 * 单实例部署时没人盯日志，某个数据源被上游停用、或网络不可达之后，
 * 运营侧唯一能观察到的信号是「岗位总数慢慢变少」，完全无法定位是哪个源出了问题。
 * 本组件把每次拉取的结果（成败 / 条数 / 耗时 / 错误摘要 / 时间）留在内存里，
 * 供管理后台的「数据源」视图与总览告警横幅读取。
 *
 * <p><b>设计取舍</b>：
 * <ul>
 *   <li>只保留「最近一次 + 连续失败次数」，不存历史序列：告警只需要回答
 *       「这个源现在还正常吗」。存历史会带来无界增长和过期清理问题，收益不匹配。</li>
 *   <li>内存态、随实例重启清空：与 {@code UserBanRegistry} 一致——重启后第一轮刷新
 *       就会重建全部状态，不值得持久化。</li>
 *   <li>失败必须留下**可读的错误摘要**而不只是一个布尔值：区分「HTTP 403 被反爬」
 *       与「连接超时」才能给出正确的处置建议（换源 vs 查网络）。</li>
 *   <li>单条错误串裁剪到 200 字：上游可能返回整页 HTML 错误页，直接塞进后台会把界面撑爆。</li>
 * </ul>
 */
@Component
public class JobSourceHealthRegistry {

    /** 错误摘要长度上限 */
    private static final int ERROR_MAX_LEN = 200;

    /**
     * 单个数据源的健康快照。
     *
     * @param platform             数据源展示名
     * @param lastAttemptAt        最近一次尝试拉取的时间（成功或失败都会更新）
     * @param lastSuccessAt        最近一次成功拉取的时间（从未成功为 null）
     * @param healthy              最近一次是否成功
     * @param lastCount            最近一次成功拉取的岗位条数
     * @param lastElapsedMs        最近一次拉取耗时（毫秒）
     * @param lastError            最近一次失败的错误摘要（成功时为 null）
     * @param consecutiveFailures  连续失败次数（成功即清零）
     */
    public record Health(
            String platform,
            LocalDateTime lastAttemptAt,
            LocalDateTime lastSuccessAt,
            boolean healthy,
            int lastCount,
            long lastElapsedMs,
            String lastError,
            int consecutiveFailures
    ) {
    }

    private final Map<String, Health> health = new ConcurrentHashMap<>();

    /** 登记一次成功拉取 */
    public void recordSuccess(String platform, int count, long elapsedMs) {
        if (platform == null) return;
        LocalDateTime now = LocalDateTime.now();
        health.put(platform, new Health(platform, now, now, true, count, elapsedMs, null, 0));
    }

    /**
     * 登记一次失败。
     *
     * <p>失败**不会**覆盖 {@code lastSuccessAt}——管理后台据此区分
     * 「刚挂（之前一直好）」与「从来没通过（配置就有问题）」。
     */
    public void recordFailure(String platform, String error, long elapsedMs) {
        if (platform == null) return;
        health.compute(platform, (k, prev) -> new Health(
                platform,
                LocalDateTime.now(),
                prev == null ? null : prev.lastSuccessAt(),
                false,
                0,
                elapsedMs,
                clip(error),
                prev == null ? 1 : prev.consecutiveFailures() + 1));
    }

    /** 全部数据源的健康快照（按平台名排序，便于后台稳定展示） */
    public List<Health> snapshot() {
        List<Health> list = new ArrayList<>(health.values());
        list.sort(Comparator.comparing(Health::platform));
        return list;
    }

    /** 指定数据源的健康快照，从未拉取过返回 null */
    public Health get(String platform) {
        return platform == null ? null : health.get(platform);
    }

    /**
     * 该数据源是否处于「刷新冷却期」。
     *
     * <p>用于给公开 API 这类不宜高频调用的源做节流：距上次尝试不足
     * {@code minIntervalMs} 则本轮跳过。注意判定用 {@code lastAttemptAt} 而非
     * {@code lastSuccessAt}——上游故障时若按成功时间判断，会退化成每轮都重试。
     *
     * @param minIntervalMs 最小间隔；≤0 表示不限制
     */
    public boolean isCoolingDown(String platform, long minIntervalMs) {
        if (platform == null || minIntervalMs <= 0) return false;
        Health h = health.get(platform);
        if (h == null || h.lastAttemptAt() == null) return false;
        return Duration.between(h.lastAttemptAt(), LocalDateTime.now()).toMillis() < minIntervalMs;
    }

    /** 需要告警的数据源：最近一次失败，或连续失败 ≥ 2 次 */
    public List<Health> alerts() {
        List<Health> list = new ArrayList<>();
        for (Health h : snapshot()) {
            if (!h.healthy() || h.consecutiveFailures() >= 2) {
                list.add(h);
            }
        }
        return list;
    }

    private static String clip(String error) {
        if (error == null || error.isBlank()) return "未知错误";
        return error.length() <= ERROR_MAX_LEN ? error : error.substring(0, ERROR_MAX_LEN);
    }
}
