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
 * 作答评分执行器（阶段六 M3）。
 *
 * <p>只支持<b>纯文本</b>评分：带附图的多模态评分需要服务端换签短时效 URL
 * （见 {@code InterviewController#evaluateAnswer} 的 P2-06），签名 10 分钟后失效，
 * 而异步任务可能在队列里等更久，签名过期会让 AI 抓不到图。因此带图请求继续走同步链路，
 * 由接口层显式回退（{@code InterviewTaskController} 拒绝 imageUrl）。
 *
 * <p>失败语义与出题一致：空响应按 503 可重试抛出，不吞异常。
 */
@Component
public class InterviewEvaluateTaskHandler implements AiTaskHandler {

    private static final Logger log = LoggerFactory.getLogger(InterviewEvaluateTaskHandler.class);

    /** 入参上限：与 {@code InterviewService} 进 Prompt 前的截断阈值保持同量级 */
    public static final int MAX_QUESTION_LEN = 1000;
    public static final int MAX_ANSWER_LEN = 5000;
    public static final int MAX_REFERENCE_LEN = 3000;

    private final InterviewService interviewService;
    private final ObjectMapper objectMapper;

    public InterviewEvaluateTaskHandler(InterviewService interviewService, ObjectMapper objectMapper) {
        this.interviewService = interviewService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String type() {
        return AiTaskTypes.INTERVIEW_EVALUATE;
    }

    @Override
    public AiTaskOutcome execute(AiTaskEntity task) {
        Payload payload = parse(task.getPayload());
        String text = interviewService.evaluateAnswerWithImage(
                payload.question(), payload.userAnswer(), payload.referenceAnswer(), null);
        if (text == null || text.isBlank()) {
            throw new BusinessException(503, "AI 返回内容为空，请稍后重试");
        }
        log.info("异步评分完成 taskId={}", task.getId());
        return AiTaskOutcome.ofText(text);
    }

    private Payload parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(400, "任务参数为空，无法评分");
        }
        Payload payload;
        try {
            payload = objectMapper.readValue(raw, Payload.class);
        } catch (Exception e) {
            throw new BusinessException(400, "任务参数格式错误，无法评分");
        }
        if (payload.question() == null || payload.userAnswer() == null) {
            throw new BusinessException(400, "问题和回答不能为空");
        }
        if (payload.question().length() > MAX_QUESTION_LEN
                || payload.userAnswer().length() > MAX_ANSWER_LEN
                || (payload.referenceAnswer() != null && payload.referenceAnswer().length() > MAX_REFERENCE_LEN)) {
            throw new BusinessException(400, "题目、回答或参考答案过长");
        }
        return payload;
    }

    /** payload 快照（字段与 {@code InterviewService#evaluateAnswerWithImage} 入参一一对应） */
    public record Payload(String question, String userAnswer, String referenceAnswer) {
        public Payload {
            referenceAnswer = referenceAnswer == null ? "" : referenceAnswer;
        }
    }
}

