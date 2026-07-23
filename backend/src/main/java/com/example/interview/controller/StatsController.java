package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.dto.DashboardStats;
import com.example.interview.entity.InterviewQuestionEntity;
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
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

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
     */
    @GetMapping("/dashboard")
    public Result<DashboardStats> dashboard() {
        String userId = currentUserId();

        // 简历数据
        List<ResumeEntity> resumes = resumeRepository.findByUserIdOrderByCreatedAtDesc(userId);
        long resumeCount = resumes.size();
        Double avgResumeScore = resumes.stream()
                .map(ResumeEntity::getOverallScore)
                .filter(s -> s != null)
                .mapToInt(Integer::intValue)
                .average()
                .stream()
                .boxed()
                .findFirst()
                .orElse(null);

        // 面试会话数据
        List<InterviewSessionEntity> sessions = sessionRepository.findByUserIdOrderByCreatedAtDesc(userId);
        long sessionCount = sessions.size();
        long finishedCount = sessions.stream()
                .filter(s -> "FINISHED".equals(s.getStatus()))
                .count();

        // 计算面试题平均分（单次批量查询，避免 N+1）
        Double avgInterviewScore = null;
        if (!sessions.isEmpty()) {
            List<String> sessionIds = sessions.stream().map(InterviewSessionEntity::getSessionId).toList();
            // 一次 IN 查询获取所有会话的题目，避免循环 N+1
            List<InterviewQuestionEntity> allQ = questionRepository.findBySessionIdInOrderByCreatedAtDesc(sessionIds);
            avgInterviewScore = allQ.stream()
                    .map(InterviewQuestionEntity::getEvaluationScore)
                    .filter(s -> s != null)
                    .mapToInt(Integer::intValue)
                    .average()
                    .stream()
                    .boxed()
                    .findFirst()
                    .orElse(null);
        }

        // 构建最近活动列表（最多 10 条，简历 + 面试混合按时间倒序）
        List<DashboardStats.RecentActivity> activities = new ArrayList<>();
        for (ResumeEntity r : resumes) {
            activities.add(new DashboardStats.RecentActivity(
                    "resume",
                    "简历分析 · " + (r.getTargetJob() == null ? "未指定岗位" : r.getTargetJob()),
                    r.getOverallScore() == null ? "未生成评分" : "综合评分 " + r.getOverallScore(),
                    r.getCreatedAt() == null ? "" : r.getCreatedAt().format(ISO)
            ));
        }
        for (InterviewSessionEntity s : sessions) {
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
}
