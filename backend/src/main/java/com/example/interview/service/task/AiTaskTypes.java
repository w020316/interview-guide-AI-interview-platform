package com.example.interview.service.task;

/**
 * AI 任务类型常量（阶段六 M3）。
 *
 * <p>任务类型是调度器与执行器唯一的分派依据（{@code ai_task.type}），
 * 前端也会按类型展示不同文案，因此集中在此，避免三处手写字符串漂移。
 */
public final class AiTaskTypes {

    /** AI 出题（payload：简历 + 岗位 + 数量 + 难度 / 聚焦项） */
    public static final String INTERVIEW_QUESTIONS = "interview.questions";

    /** 作答评分（payload：题目 + 用户回答 + 参考答案） */
    public static final String INTERVIEW_EVALUATE = "interview.evaluate";

    private AiTaskTypes() {
    }
}
