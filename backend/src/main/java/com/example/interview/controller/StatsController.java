package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.dto.DashboardStats;
import com.example.interview.entity.InterviewSessionEntity;
import com.example.interview.entity.ResumeEntity;
import com.example.interview.repository.InterviewQuestionRepository;
import com.example.interview.repository.InterviewSessionRepository;
import com.example.interview.repository.ResumeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 用户统计接口
 * GET /api/stats/dashboard - 个人中心首页统计数据
 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    @Autowired
    private ResumeRepository resumeRepository;

    @Autowired
    private InterviewSessionRepository sessionRepository;

    @Autowired
    private InterviewQuestionRepository questionRepository;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    /** 从 SecurityContext 获取当前登录用户 ID */
    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            throw new IllegalStateException("未认证用户");
        }
        return auth.getPrincipal().toString();
    }

    /**
     * 个人中心仪表盘统计
     * v1.23.3：统计走数据库聚合（count/avg），活动列表只取近 10 条，
     * 不再将该用户全部简历/会话/题目实体加载进内存
     */
    @GetMapping("/dashboard")
    public Result<DashboardStats> dashboard() {
        String userId = currentUserId();

        // 简历数据（count/avg 由数据库聚合；活动列表仅取近 10 条）
        long resumeCount = resumeRepository.countByUserId(userId);
        Double avgResumeScore = resumeRepository.avgOverallScoreByUserId(userId);
        List<ResumeEntity> recentResumes = resumeRepository.findTop10ByUserIdOrderByCreatedAtDesc(userId);

        // 面试会话数据（数据库聚合）
        long sessionCount = sessionRepository.countByUserId(userId);
        long finishedCount = sessionRepository.countByUserIdAndStatus(userId, "FINISHED");
        List<InterviewSessionEntity> recentSessions = sessionRepository.findTop10ByUserIdOrderByCreatedAtDesc(userId);

        // 全部题目平均分：数据库子查询聚合（此前需加载全部会话+题目实体）
        Double avgInterviewScore = questionRepository.avgEvaluationScoreByUserId(userId);

        // 构建最近活动列表（近 10 条，简历 + 面试混合按时间倒序）
        List<DashboardStats.RecentActivity> activities = new ArrayList<>();
        for (ResumeEntity r : recentResumes) {
            activities.add(new DashboardStats.RecentActivity(
                    "resume",
                    "简历分析 · " + (r.getTargetJob() == null ? "未指定岗位" : r.getTargetJob()),
                    r.getOverallScore() == null ? "未生成评分" : "综合评分 " + r.getOverallScore(),
                    r.getCreatedAt() == null ? "" : r.getCreatedAt().format(ISO)
            ));
        }
        for (InterviewSessionEntity s : recentSessions) {
            activities.add(new DashboardStats.RecentActivity(
                    "interview",
                    "模拟面试 · " + (s.getJobDescription() == null ? "未指定岗位" : s.getJobDescription()),
                    "FINISHED".equals(s.getStatus()) ? "已完成" : "进行中",
                    s.getCreatedAt() == null ? "" : s.getCreatedAt().format(ISO)
            ));
        }
        activities.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
        if (activities.size() > 10) {
            activities = activities.subList(0, 10);
        }

        return Result.success(new DashboardStats(
                resumeCount,
                sessionCount,
                finishedCount,
                avgResumeScore,
                avgInterviewScore,
                activities
        ));
    }

    /**
     * 面试成绩成长趋势：按「已完成的会话」聚合每次面试的综合得分
     * GET /api/stats/trend?dimension=DAY|WEEK|MONTH
     *
     * 综合得分 = 该会话所有已评分题目的平均分（0-100），按时间升序返回，
     * 用于前端绘制成长折线图。
     * dimension：DAY 按每次面试（默认）；WEEK 按周聚合（平均分）；MONTH 按月聚合。
     */
    @GetMapping("/trend")
    public Result<List<Map<String, Object>>> trend(
            @RequestParam(value = "dimension", defaultValue = "DAY") String dimension) {
        String userId = currentUserId();

        // 已完成会话，按时间升序（数据库按状态过滤，避免全量加载后内存筛选）
        List<InterviewSessionEntity> finished =
                sessionRepository.findByUserIdAndStatusOrderByCreatedAtAsc(userId, "FINISHED");
        if (finished.isEmpty()) {
            return Result.success(List.of());
        }

        // 按会话聚合平均分与已评分题数（数据库 GROUP BY，不再加载全部题目实体）
        List<String> sessionIds = finished.stream().map(InterviewSessionEntity::getSessionId).toList();
        Map<String, double[]> scoreBySession = new java.util.HashMap<>(); // [avg, count]
        for (Object[] row : questionRepository.avgScoreGroupBySession(sessionIds)) {
            String sid = (String) row[0];
            double avg = ((Number) row[1]).doubleValue();
            long cnt = ((Number) row[2]).longValue();
            scoreBySession.put(sid, new double[]{avg, cnt});
        }

        // 1. 计算每个会话的综合得分（跳过无评分记录的会话，与聚合查询结果对齐）
        List<SessionPoint> sessionPoints = new ArrayList<>();
        for (InterviewSessionEntity s : finished) {
            double[] agg = scoreBySession.get(s.getSessionId());
            if (agg == null) continue; // 无评分记录不产生数据点
            double avg = Math.round(agg[0] * 10) / 10.0;
            sessionPoints.add(new SessionPoint(s, avg, (int) agg[1]));
        }
        if (sessionPoints.isEmpty()) {
            return Result.success(List.of());
        }

        // 2. 按维度聚合（DAY 保持单次；WEEK/MONTH 分组取平均）
        List<Map<String, Object>> points = new ArrayList<>();
        String dim = dimension == null ? "DAY" : dimension.trim().toUpperCase(Locale.ROOT);
        if ("DAY".equals(dim) || sessionPoints.size() < 2) {
            int i = 1;
            for (SessionPoint p : sessionPoints) {
                points.add(buildPoint(p.session, p.score, p.questionCount, i));
                i++;
            }
        } else {
            boolean byMonth = "MONTH".equals(dim);
            // LinkedHashMap 保证组间按时间升序
            Map<String, List<SessionPoint>> groups = new LinkedHashMap<>();
            for (SessionPoint p : sessionPoints) {
                LocalDate date = p.session.getCreatedAt() != null ? p.session.getCreatedAt().toLocalDate() : null;
                String key;
                if (byMonth) {
                    key = date == null ? "unknown" : String.format("%04d-%02d", date.getYear(), date.getMonthValue());
                } else {
                    key = date == null ? "unknown" : startOfWeek(date).toString();
                }
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
            }
            int i = 1;
            for (Map.Entry<String, List<SessionPoint>> e : groups.entrySet()) {
                if ("unknown".equals(e.getKey())) continue;
                List<SessionPoint> group = e.getValue();
                double groupAvg = group.stream().mapToDouble(SessionPoint::score).average().orElse(0);
                int questionCount = group.stream().mapToInt(SessionPoint::questionCount).sum();
                LocalDate anchor = group.get(0).session.getCreatedAt().toLocalDate();
                String label;
                if (byMonth) {
                    label = e.getKey() + " 月";
                } else {
                    LocalDate ws = startOfWeek(group.get(0).session.getCreatedAt().toLocalDate());
                    label = monthDay(ws) + "~" + monthDay(ws.plusDays(6));
                }
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("seq", i);
                point.put("date", anchor.toString());
                point.put("label", label);
                point.put("score", Math.round(groupAvg * 10) / 10.0);
                point.put("questionCount", questionCount);
                point.put("jobTitle", group.size() + " 次面试");
                point.put("sessionId", group.get(0).session.getSessionId());
                points.add(point);
                i++;
            }
        }
        return Result.success(points);
    }

    /** 会话单次点（内部聚合辅助） */
    private record SessionPoint(InterviewSessionEntity session, double score, int questionCount) {
    }

    /** 某日期所在周的周一 */
    private static LocalDate startOfWeek(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /** 简短格式化：M-d */
    private static String monthDay(LocalDate date) {
        return date.getMonthValue() + "-" + date.getDayOfMonth();
    }

    /** 构建单个趋势点 */
    private Map<String, Object> buildPoint(InterviewSessionEntity s, double avg, int questionCount, int seq) {
        LocalDate date = s.getCreatedAt() != null ? s.getCreatedAt().toLocalDate() : null;
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("seq", seq);
        point.put("date", date == null ? "" : date.toString());
        point.put("label", date == null ? ("第" + seq + "次") : date.toString());
        point.put("score", avg);
        point.put("questionCount", questionCount);
        point.put("jobTitle", s.getJobDescription() == null || s.getJobDescription().isBlank()
                ? "未指定岗位" : truncate(s.getJobDescription()));
        point.put("sessionId", s.getSessionId());
        return point;
    }

    /** 截断过长的岗位描述，避免前端展示溢出 */
    private String truncate(String text) {
        return text.length() > 24 ? text.substring(0, 24) + "…" : text;
    }
}
