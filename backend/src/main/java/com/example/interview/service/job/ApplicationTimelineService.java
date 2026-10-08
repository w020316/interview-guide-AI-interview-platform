package com.example.interview.service.job;

import com.example.interview.entity.InterviewEventEntity;
import com.example.interview.entity.JobApplicationEntity;
import com.example.interview.service.InterviewEventService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 投递 ↔ 面试时序视图服务（v1.61.0，竞品清单 #4 reactive-resume 的做法）。
 *
 * <p><b>要解决什么</b>：投递台账（{@code job_application}）与面试日历（{@code interview_event}）
 * 目前是两张互不相干的表——用户能看到「我投过什么」和「我什么时候面试」，但看不到
 * <b>「投了 A 之后多久接到面试」</b>这条线索。本服务把两边的真实时间点拉平成一条时间线。
 *
 * <p><b>合规红线（本项目铁律，勿越界）</b>：
 * <ol>
 *   <li><b>不编造时间点</b>：只输出数据库里真实存在的时间字段。上游/用户没填的一律不出现在
 *       时间线上——<b>绝不用「创建时间」冒充「投递时间」</b>。</li>
 *   <li><b>关联只按用户已建立的事实</b>：日历事件与投递记录的关联来自
 *       {@code InterviewEventService} 已落库的 {@code applicationId}（v1.61.0 新增列），
 *       <b>不做标题字符串模糊匹配</b>——那会在重名岗位（如两家公司都叫「后端工程师」）上
 *       产生假关联，而假关联比没有关联更糟。</li>
 *   <li><b>不推算「投递到面试用了几天」这类派生值以外的任何东西</b>：天数差是本服务唯一允许的
 *       计算，且仅在两个端点都存在时才产出。</li>
 * </ol>
 *
 * <p>时区：全部字段为 {@link LocalDateTime}（无时区），与既有实体口径一致。前端按本地时区展示。
 */
@Service
public class ApplicationTimelineService {

    /** 时间线节点类型 */
    public static final String KIND_APPLIED = "APPLIED";
    public static final String KIND_INTERVIEW = "INTERVIEW";
    public static final String KIND_STATUS = "STATUS";

    /** 已取消的面试不计入任何统计（取消了还提示「你已安排面试」就是误导） */
    private static final String EVENT_STATUS_CANCELLED = "CANCELLED";

    /**
     * 「面试之前」的投递状态。命中这些状态、却已关联了未取消的面试 → 视为**状态滞后**。
     *
     * <p>⚠️ 刻意**不含** OFFER / REJECTED / WITHDRAWN：它们是终态，
     * 面试后走到这里**是正常的**，再提示用户「去改成面试中」反而是错的。
     */
    private static final Set<String> STATUS_BEFORE_INTERVIEW = Set.of(
            JobApplicationEntity.STATUS_PLANNED,
            JobApplicationEntity.STATUS_APPLIED,
            JobApplicationEntity.STATUS_VIEWED,
            JobApplicationEntity.STATUS_REPLIED);

    @Autowired
    private JobApplicationService applicationService;

    @Autowired
    private InterviewEventService eventService;

    /**
     * 构建当前用户的完整时间线。
     *
     * <p>返回结构：
     * <pre>
     * {
     *   "nodes": [ { "kind": "INTERVIEW", "at": "2026-10-08T14:00", "applicationId": 12,
     *                "title": "...", "companyName": "...", "stage": "INTERVIEW",
     *                "location": "...", "note": "...", "eventStatus": "UPCOMING",
     *                "daysSinceApplied": 3 } ],
     *   "stats": { "appliedCount": 5, "interviewCount": 3, "linkedInterviewCount": 2,
     *              "unlinkedInterviewCount": 1,
     *              "avgDaysToInterview": 4.0, "medianDaysToInterview": 4.0,
     *              "interviewSampleSize": 2 }
     * }
     * </pre>
     *
     * <p><b>{@code avgDaysToInterview} 的含义被严格限定</b>：仅统计「已建立关联」的
     * 投递↔面试对（{@code interviewSampleSize} 为样本数）。样本为 0 时返回 {@code null}
     * ——<b>不产出假 0</b>（本项目纪律：无数据 ≠ 0）。
     */
    public Map<String, Object> timeline(String userId) {
        List<JobApplicationEntity> apps = applicationService.listByUser(userId);
        List<InterviewEventEntity> events = eventService.listByUser(userId);

        Map<Long, JobApplicationEntity> appById = new LinkedHashMap<>();
        for (JobApplicationEntity a : apps) {
            appById.put(a.getId(), a);
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (JobApplicationEntity a : apps) {
            // 只有真正投出过（appliedAt 非空）才在时间线上留一个「投递」节点。
            // PLANNED 草稿没有投出时间——用 createdAt 顶上就是编造。
            if (a.getAppliedAt() != null) {
                nodes.add(appliedNode(a));
            }
        }

        List<Double> daysToInterview = new ArrayList<>();
        int linked = 0;
        int unlinked = 0;
        for (InterviewEventEntity e : events) {
            JobApplicationEntity linked2 = resolveLinked(e, appById);
            if (linked2 == null) {
                unlinked++;
            } else {
                linked++;
                daysToInterview.add(daysBetween(linked2.getAppliedAt(), e.getInterviewAt()));
            }
            nodes.add(interviewNode(e, linked2, daysToInterview));
        }

        nodes.sort(Comparator
                .comparing((Map<String, Object> n) -> String.valueOf(n.get("at")))
                .thenComparing(n -> String.valueOf(n.get("kind"))));

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("appliedCount", nodes.stream().filter(n -> KIND_APPLIED.equals(n.get("kind"))).count());
        stats.put("interviewCount", events.size());
        stats.put("linkedInterviewCount", linked);
        stats.put("unlinkedInterviewCount", unlinked);
        stats.put("interviewSampleSize", daysToInterview.size());
        stats.put("avgDaysToInterview", average(daysToInterview));
        stats.put("medianDaysToInterview", median(daysToInterview));

        // ── 状态滞后：已安排面试，但投递状态还停在面试之前（v1.65.0）──
        // 用户在日历里安排了面试并关联了投递，看板上的状态却仍停在「已投递」：
        // 漏斗里的「面试率」因此失真，而用户通常不会回头手动改。
        // 这里只**如实列出**，**不擅自替用户改状态** —— 一键推进由用户自己点。
        Map<Long, LocalDateTime> firstInterviewAt = new LinkedHashMap<>();
        for (InterviewEventEntity e : events) {
            if (EVENT_STATUS_CANCELLED.equals(e.getStatus())) continue;
            JobApplicationEntity a = resolveLinked(e, appById);
            if (a == null || !STATUS_BEFORE_INTERVIEW.contains(a.getStatus())) continue;
            if (e.getInterviewAt() == null) continue;
            // 同一投递关联多场面试时取最早那场：用户要判断的是「第一面是什么时候」
            firstInterviewAt.merge(a.getId(), e.getInterviewAt(),
                    (x, y) -> x.isBefore(y) ? x : y);
        }
        List<Map<String, Object>> statusLagging = new ArrayList<>();
        for (Map.Entry<Long, LocalDateTime> en : firstInterviewAt.entrySet()) {
            JobApplicationEntity a = appById.get(en.getKey());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("applicationId", a.getId());
            m.put("title", a.getTitle());
            m.put("companyName", a.getCompanyName());
            m.put("status", a.getStatus());
            m.put("statusLabel", JobApplicationService.label(a.getStatus()));
            m.put("interviewAt", en.getValue().toString());
            statusLagging.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", nodes);
        result.put("stats", stats);
        result.put("statusLagging", statusLagging);
        return result;
    }

    /**
     * 关联日历事件到投递记录：<b>只认 {@code applicationId} 外键</b>。
     *
     * <p>刻意不做「标题包含公司名」之类的模糊匹配——见类注释红线 2。
     * 用户在日历里手工建的日程（从未关联投递）保持 {@code applicationId == null}，
     * 前端会如实显示为「未关联投递」，而不是硬塞给某条记录。
     */
    private JobApplicationEntity resolveLinked(InterviewEventEntity e,
                                              Map<Long, JobApplicationEntity> appById) {
        if (e.getApplicationId() == null) {
            return null;
        }
        return appById.get(e.getApplicationId());
    }

    private Map<String, Object> appliedNode(JobApplicationEntity a) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("kind", KIND_APPLIED);
        n.put("at", a.getAppliedAt().toString());
        n.put("applicationId", a.getId());
        n.put("title", a.getTitle());
        n.put("companyName", a.getCompanyName());
        n.put("stage", a.getStatus());
        n.put("stageLabel", JobApplicationService.label(a.getStatus()));
        n.put("channel", channelOf(a));
        n.put("applyUrl", a.getApplyUrl());
        return n;
    }

    private Map<String, Object> interviewNode(InterviewEventEntity e,
                                             JobApplicationEntity linked,
                                             List<Double> daysToInterview) {
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("kind", KIND_INTERVIEW);
        n.put("at", e.getInterviewAt().toString());
        n.put("eventId", e.getId());
        n.put("applicationId", linked == null ? null : linked.getId());
        n.put("title", linked == null ? e.getTitle() : linked.getTitle());
        n.put("companyName", linked == null ? null : linked.getCompanyName());
        n.put("location", e.getLocation());
        n.put("interviewer", e.getInterviewer());
        n.put("note", e.getNote());
        n.put("eventStatus", e.getStatus());
        // 派生值仅在两端都有真实时间时产出；否则明确给 null（不猜、不补 0）
        if (linked != null && linked.getAppliedAt() != null) {
            n.put("daysSinceApplied", daysBetween(linked.getAppliedAt(), e.getInterviewAt()));
        } else {
            n.put("daysSinceApplied", null);
        }
        return n;
    }

    /** 渠道归一：与 {@link JobApplicationService} 的「未标注」口径一致 */
    private static String channelOf(JobApplicationEntity a) {
        String p = a.getPlatform();
        return (p == null || p.isBlank()) ? "未标注" : p.trim();
    }

    /**
     * 两个时间点相差的天数（四舍五入到 1 位小数）。
     *
     * <p>用 {@link Duration} 精确到分钟再折算——直接减 {@code toEpochDay()} 会丢掉
     * 同日内的时分差，把「当天投、当天面」算成 0 天（其实可能是 6 小时，即 0.3 天）。
     */
    static double daysBetween(LocalDateTime from, LocalDateTime to) {
        long minutes = Duration.between(from, to).toMinutes();
        return Math.round(minutes / 1440.0 * 10.0) / 10.0;
    }

    /** 平均天数；样本为空返回 {@code null}（不产出假 0） */
    static Double average(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return Math.round(sum / values.size() * 10.0) / 10.0;
    }

    /** 中位数；样本为空返回 {@code null} */
    static Double median(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int n = sorted.size();
        double m = (n % 2 == 1)
                ? sorted.get(n / 2)
                : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
        return Math.round(m * 10.0) / 10.0;
    }
}
