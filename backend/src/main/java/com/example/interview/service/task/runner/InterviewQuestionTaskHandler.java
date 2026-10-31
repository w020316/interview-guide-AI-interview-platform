package com.example.interview.service.task.runner;

import com.example.interview.common.BusinessException;
import com.example.interview.entity.AiTaskEntity;
import com.example.interview.service.InterviewService;
import com.example.interview.service.task.AiTaskHandler;
import com.example.interview.service.task.AiTaskOutcome;
import com.example.interview.service.task.AiTaskTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * AI 出题执行器（阶段六 M3）。
 *
 * <p>只做三件事：<b>解析 payload → 交给 {@link InterviewService} → 把结果正文回给调度器</b>。
 * 真正的出题逻辑（RAG 检索、缓存、Prompt、并发闸门）一律复用同步链路，
 * 避免异步版本悄悄偏离已修复过的行为。
 *
 * <p>几个刻意的取舍：
 * <ul>
 *   <li><b>不吞异常</b>：AI 失败必须抛出，由调度器判定是否重试；</li>
 *   <li><b>入参再次校验</b>：payload 来自数据库，可能是通用 {@code POST /api/tasks}
 *       塞进来的，不能假设已经过接口层校验；</li>
 *   <li><b>幂等</b>：出题本身按 userId + 内容缓存（{@link InterviewService#generateQuestions}），
 *       重复执行不会重复计费，也不会写业务表。</li>
 * </ul>
 */
@Component
public class InterviewQuestionTaskHandler implements AiTaskHandler {

    private static final Logger log = LoggerFactory.getLogger(InterviewQuestionTaskHandler.class);

    /** 入参上限：与同步接口一致（{@code InterviewService} 内部还会按 800 截断进 Prompt） */
    public static final int MAX_RESUME_LEN = 2000;
    public static final int MAX_JD_LEN = 2000;
    public static final int MAX_FOCUS_LEN = 200;
    public static final int MIN_COUNT = 1;
    public static final int MAX_COUNT = 20;

    private final InterviewService interviewService;
    private final ObjectMapper objectMapper;

    public InterviewQuestionTaskHandler(InterviewService interviewService, ObjectMapper objectMapper) {
        this.interviewService = interviewService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String type() {
        return AiTaskTypes.INTERVIEW_QUESTIONS;
    }

    @Override
    public AiTaskOutcome execute(AiTaskEntity task) {
        Payload payload = parse(task.getPayload());
        String text = interviewService.generateQuestions(
                task.getUserId(), payload.resumeText(), payload.jobDescription(),
                payload.count(), payload.difficulty(), payload.focusCategories(), payload.regenerate());
        if (text == null || text.isBlank()) {
            // 空响应不能算成功：前端拿到空串只会显示「生成失败」，且任务会被标成已完成
            throw new BusinessException(503, "AI 返回内容为空，请稍后重试");
        }
        log.info("异步出题完成 taskId={} questions={}", task.getId(), payload.count());
        return AiTaskOutcome.ofText(text);
    }

    private Payload parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(400, "任务参数为空，无法出题");
        }
        Payload payload;
        try {
            payload = objectMapper.readValue(raw, Payload.class);
        } catch (Exception e) {
            throw new BusinessException(400, "任务参数格式错误，无法出题");
        }
        if (payload.resumeText() == null || payload.jobDescription() == null) {
            throw new BusinessException(400, "简历和岗位描述不能为空");
        }
        if (payload.resumeText().length() > MAX_RESUME_LEN || payload.jobDescription().length() > MAX_JD_LEN) {
            throw new BusinessException(400, "简历或岗位描述过长");
        }
        String focus = payload.focusCategories();
        if (focus != null && focus.length() > MAX_FOCUS_LEN) {
            throw new BusinessException(400, "聚焦分类过长");
        }
        return payload;
    }

    /** payload 快照（字段与 {@code InterviewService#generateQuestions} 入参一一对应） */
    public record Payload(String resumeText, String jobDescription, int count,
                          String difficulty, String focusCategories, boolean regenerate) {
        public Payload {
            // 数量越界一律夹到合法区间：与同步接口的夹取行为保持一致，而不是拒绝请求
            count = Math.max(MIN_COUNT, Math.min(MAX_COUNT, count));
            difficulty = difficulty == null ? "" : difficulty.trim();
            focusCategories = focusCategories == null ? "" : focusCategories.trim();
        }
    }
}

