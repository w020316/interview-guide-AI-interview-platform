package com.example.interview.service;

import com.example.interview.entity.InterviewEventEntity;
import com.example.interview.entity.JobApplicationEntity;
import com.example.interview.entity.JobFavoriteEntity;
import com.example.interview.service.job.JobApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 今日待办聚合服务（第三批 B，read-time，零 AI）。
 *
 * <p>把分散在四个页面的「今天该做什么」聚合成一个只读视图，避免用户自行翻页拼凑。
 * 四组：
 * <ol>
 *   <li>{@code FOLLOW_UP} 待跟进投递 —— 复用 {@link JobApplicationService#needsFollowUp}；</li>
 *   <li>{@code UPCOMING} 近期日程 —— 日历中时间落在窗口内的日程；</li>
 *   <li>{@code DEADLINE} 临近截止 —— 岗位收藏中截止日在窗口内的项；</li>
 *   <li>{@code PLANNED} 待投递 —— 台账中处于 PLANNED 的记录。</li>
 * </ol>
 *
 * <p><b>局部失败隔离</b>：任一组的数据源异常不影响其余组；该组 {@code count=null}、
 * {@code available=false}，并进入 {@code partialFailures}。
 * {@code count=null} 与 {@code count=0} 语义严格区分：前者=「数据源暂时取不到」，
 * 后者=「确实没有待办」（R3「无数据 ≠ 0」）。前端对空组直接隐藏、对 null 组显示局部失败态。
 *
 * <p><b>零 AI</b>：本服务不做任何 AI 调用，纯规则聚合。
 */
@Service
public class TodoService {

    private static final Logger log = LoggerFactory.getLogger(TodoService.class);

    /** 默认日程/截止窗口天数 */
    private static final int DEFAULT_WINDOW_DAYS = 3;
    /** 窗口天数上限，避免异常入参拉长查询范围 */
    private static final int MAX_WINDOW_DAYS = 30;

    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("MM月dd日");

    @Autowired
    private JobApplicationService applicationService;

    @Autowired
    private InterviewEventService eventService;

    @Autowired
    private JobFavoriteService favoriteService;

    /** 四个分组的组标识与中文标签（顺序即展示顺序） */
    private static final String[] GROUP_KEYS = {"FOLLOW_UP", "UPCOMING", "DEADLINE", "PLANNED"};
    private static final String[] GROUP_LABELS = {"待跟进投递", "近期日程", "临近截止", "待投递"};

    /**
     * 聚合今日待办。
     *
     * @param userId 当前用户 ID
     * @param days   日程/截止窗口天数（&le;0 时取默认 3，最大 30）
     * @return {@code {generatedAt, groups:[...], partialFailures:[...]}}
     */
    public Map<String, Object> today(String userId, int days) {
        int window = resolveWindow(days);
        LocalDateTime now = LocalDateTime.now();

        List<Map<String, Object>> partialFailures = new ArrayList<>();
        List<Map<String, Object>> groups = new ArrayList<>();

        groups.add(buildGroup(GROUP_KEYS[0], GROUP_LABELS[0], partialFailures,
                () -> followUpItems(userId, now)));
        groups.add(buildGroup(GROUP_KEYS[1], GROUP_LABELS[1], partialFailures,
                () -> upcomingItems(userId, now, window)));
        groups.add(buildGroup(GROUP_KEYS[2], GROUP_LABELS[2], partialFailures,
                () -> deadlineItems(userId, now, window)));
        groups.add(buildGroup(GROUP_KEYS[3], GROUP_LABELS[3], partialFailures,
                () -> plannedItems(userId)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", System.currentTimeMillis());
        result.put("groups", groups);
        result.put("partialFailures", partialFailures);
        return result;
    }

    /** 计算窗口天数：非法/过小取默认，过大取上限。 */
    static int resolveWindow(int days) {
        if (days <= 0) {
            return DEFAULT_WINDOW_DAYS;
        }
        return Math.min(days, MAX_WINDOW_DAYS);
    }

    /** 单组数据供给器，允许抛异常以触发局部失败隔离。 */
    @FunctionalInterface
    interface GroupSupplier {
        List<Map<String, Object>> get();
    }

    /** 组装一组；异常时降级为 count=null + partialFailures 记录（不整页失败）。 */
    private Map<String, Object> buildGroup(String key, String label,
                                           List<Map<String, Object>> partialFailures,
                                           GroupSupplier supplier) {
        List<Map<String, Object>> items;
        boolean available = true;
        try {
            items = supplier.get();
        } catch (Exception e) {
            log.warn("今日待办分组「{}」聚合失败：{}", label, e.getMessage());
            items = List.of();
            available = false;
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("group", key);
            failure.put("message", "「" + label + "」数据暂时取不到");
            partialFailures.add(failure);
        }
        Map<String, Object> group = new LinkedHashMap<>();
        group.put("key", key);
        group.put("label", label);
        // count=null 表示数据源不可用（区别于 0=确实没有待办）
        group.put("count", available ? items.size() : null);
        group.put("available", available);
        group.put("items", items);
        return group;
    }

    /** 待跟进投递：复用台账的 read-time 跟进判定。 */
    private List<Map<String, Object>> followUpItems(String userId, LocalDateTime now) {
        List<JobApplicationEntity> apps = applicationService.listByUser(userId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (JobApplicationEntity a : apps) {
            if (applicationService.needsFollowUp(a, now)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", a.getId());
                item.put("title", joinCompanyTitle(a.getCompanyName(), a.getTitle()));
                item.put("subtitle", followUpSubtitle(a, now));
                item.put("reason", followUpReason(a, now));
                item.put("route", "/applications");
                item.put("routeQuery", Map.of("focus", a.getId()));
                items.add(item);
            }
        }
        return items;
    }

    private String followUpSubtitle(JobApplicationEntity a, LocalDateTime now) {
        if (a.getAppliedAt() != null) {
            long days = ChronoUnit.DAYS.between(a.getAppliedAt(), now);
            return "已投递 " + Math.max(days, 0) + " 天无更新";
        }
        return "待跟进";
    }

    private String followUpReason(JobApplicationEntity a, LocalDateTime now) {
        if (a.getNextActionAt() != null && !a.getNextActionAt().isAfter(now)) {
            return "计划跟进时间 " + a.getNextActionAt().toLocalDate().format(MD) + " 已到";
        }
        long days = a.getAppliedAt() != null
                ? ChronoUnit.DAYS.between(a.getAppliedAt(), now)
                : 0;
        return "投出后 " + Math.max(days, 0) + " 天无状态变化（阈值 7 天）";
    }

    /** 近期日程：时间落在 [now, now+window] 且未被标记为已完成/取消。 */
    private List<Map<String, Object>> upcomingItems(String userId, LocalDateTime now, int window) {
        List<InterviewEventEntity> events = eventService.listByUser(userId);
        LocalDateTime upper = now.plusDays(window);
        List<Map<String, Object>> items = new ArrayList<>();
        for (InterviewEventEntity e : events) {
            if (e.getInterviewAt() == null) {
                continue;
            }
            if (e.getInterviewAt().isBefore(now) || e.getInterviewAt().isAfter(upper)) {
                continue;
            }
            if ("DONE".equals(e.getStatus()) || "CANCELLED".equals(e.getStatus())) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", e.getId());
            item.put("title", e.getTitle());
            item.put("subtitle", e.getInterviewAt().toLocalDate().format(MD) + " "
                    + String.format("%02d:%02d", e.getInterviewAt().getHour(), e.getInterviewAt().getMinute()));
            item.put("reason", upcomingReason(e.getInterviewAt(), now));
            item.put("route", "/calendar");
            item.put("routeQuery", Map.of("focus", e.getId()));
            items.add(item);
        }
        return items;
    }

    private String upcomingReason(LocalDateTime at, LocalDateTime now) {
        long days = ChronoUnit.DAYS.between(now.toLocalDate(), at.toLocalDate());
        if (days <= 0) {
            return "就在今天";
        }
        return "还有 " + days + " 天";
    }

    /** 临近截止：收藏岗位截止日落在 [today, today+window]。 */
    private List<Map<String, Object>> deadlineItems(String userId, LocalDateTime now, int window) {
        List<JobFavoriteEntity> favorites = favoriteService.listByUser(userId);
        LocalDate today = now.toLocalDate();
        LocalDate upper = today.plusDays(window);
        List<Map<String, Object>> items = new ArrayList<>();
        for (JobFavoriteEntity f : favorites) {
            LocalDate deadline = f.getDeadline();
            if (deadline == null || deadline.isBefore(today) || deadline.isAfter(upper)) {
                continue;
            }
            long daysLeft = ChronoUnit.DAYS.between(today, deadline);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", f.getId());
            item.put("title", joinCompanyTitle(f.getCompanyName(), f.getTitle()));
            item.put("subtitle", deadline.format(MD) + "（还剩 " + daysLeft + " 天）");
            item.put("reason", "截止日期临近（" + daysLeft + " 天内）");
            item.put("route", "/jobs");
            item.put("routeQuery", Map.of("focus", f.getJobId()));
            items.add(item);
        }
        return items;
    }

    /** 待投递：台账中仍为 PLANNED 的记录。 */
    private List<Map<String, Object>> plannedItems(String userId) {
        List<JobApplicationEntity> apps = applicationService.listByUser(userId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (JobApplicationEntity a : apps) {
            if (!JobApplicationEntity.STATUS_PLANNED.equals(a.getStatus())) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", a.getId());
            item.put("title", joinCompanyTitle(a.getCompanyName(), a.getTitle()));
            item.put("subtitle", "待投递");
            item.put("reason", "已加入台账但尚未投递");
            item.put("route", "/applications");
            item.put("routeQuery", Map.of("focus", a.getId()));
            items.add(item);
        }
        return items;
    }

    private static String joinCompanyTitle(String company, String title) {
        String c = company == null ? "" : company;
        String t = title == null ? "" : title;
        if (c.isBlank()) {
            return t;
        }
        if (t.isBlank()) {
            return c;
        }
        return c + " · " + t;
    }
}
