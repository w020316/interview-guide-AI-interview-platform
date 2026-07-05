package com.example.interview.dto;

import java.util.List;

/**
 * 用户仪表盘统计数据
 * 用于个人中心首页展示
 */
public record DashboardStats(
        long resumeCount,           // 简历数量
        long sessionCount,          // 面试会话数量
        long finishedSessionCount, // 已完成面试数
        Double avgResumeScore,     // 简历平均分
        Double avgInterviewScore, // 面试平均分
        List<RecentActivity> recentActivities // 最近活动列表
) {
    /**
     * 最近活动项
     */
    public record RecentActivity(
            String type,        // resume / interview
            String title,       // 标题
            String description, // 描述
            String createdAt    // 创建时间 ISO 字符串
    ) {}
}
