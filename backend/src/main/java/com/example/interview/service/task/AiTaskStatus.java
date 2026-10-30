package com.example.interview.service.task;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * AI 任务状态机（阶段六 M1）。
 *
 * <p>合法流转（其余一律视为冲突，由服务层拒绝并返回 409）：
 * <pre>
 *   QUEUED   → RUNNING | CANCELLED
 *   RUNNING  → SUCCEEDED | FAILED | QUEUED（可重试失败，回到队列） | CANCELLED
 *   FAILED   → QUEUED（人工重试，M2 起提供入口）
 *   SUCCEEDED / CANCELLED → 终态，不再流转
 * </pre>
 *
 * <p>为什么把状态机单独抽出来：状态散落在多个 service 方法里时，「什么状态允许什么操作」
 * 只能靠注释记忆，改一处漏一处；集中成枚举 + 判定函数后，单测可以逐条锁定。
 */
public enum AiTaskStatus {

    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    /** 「未失败」状态：幂等键在这些状态下复用原任务（FAILED 之后允许同键新建） */
    public static final Set<String> ACTIVE = Set.of(QUEUED.name(), RUNNING.name(), SUCCEEDED.name());

    private static final Map<AiTaskStatus, Set<AiTaskStatus>> ALLOWED = Map.of(
            QUEUED, EnumSet.of(RUNNING, CANCELLED),
            RUNNING, EnumSet.of(SUCCEEDED, FAILED, QUEUED, CANCELLED),
            FAILED, EnumSet.of(QUEUED),
            SUCCEEDED, EnumSet.noneOf(AiTaskStatus.class),
            CANCELLED, EnumSet.noneOf(AiTaskStatus.class)
    );

    /** 是否允许从 {@code from} 流转到 {@code to}；未知/空状态一律不允许 */
    public static boolean canTransit(String from, String to) {
        AiTaskStatus f = parseOrNull(from);
        AiTaskStatus t = parseOrNull(to);
        if (f == null || t == null) {
            return false;
        }
        return ALLOWED.getOrDefault(f, Set.of()).contains(t);
    }

    /** 是否终态（不可再流转） */
    public static boolean isTerminal(String status) {
        AiTaskStatus s = parseOrNull(status);
        return s == SUCCEEDED || s == CANCELLED;
    }

    private static AiTaskStatus parseOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
