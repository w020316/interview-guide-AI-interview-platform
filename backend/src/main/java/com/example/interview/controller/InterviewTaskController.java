package com.example.interview.controller;

import com.example.interview.common.RateLimitExceededException;
import com.example.interview.common.Result;
import com.example.interview.entity.AiTaskEntity;
import com.example.interview.service.task.AiTaskService;
import com.example.interview.service.task.AiTaskTypes;
import com.example.interview.service.task.runner.InterviewEvaluateTaskHandler;
import com.example.interview.service.task.runner.InterviewQuestionTaskHandler;
import com.example.interview.util.HashUtil;
import com.example.interview.util.PerUserRateLimiter;
import com.example.interview.util.RequestFieldUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 出题 / 评分的异步入口（阶段六 M3）。
 *
 * <h2>为什么不改原有同步接口</h2>
 *
 * <p>原有 {@code POST /api/interview/questions} 与 {@code /evaluate} 的响应体是「题目 JSON 原文」，
 * 前端、移动端、测试都按这个契约写。若在开关开启时把同一个 URL 改成返回 {@code taskId}，
 * 就变成「同一路径两种响应形态」——回滚开关只能靠后端，客户端无法自适应。
 *
 * <p>因此 M3 的做法是<b>新增</b>异步入口（本类），前端优先走异步、失败即回退同步；
 * 开关关闭时本类统一返回 503「任务中心未启用」，前端据此回退，
 * 于是「关掉开关 = 完全回到同步链路」由这一点结构性保证，而不是依赖前端版本。
 *
 * <h2>鉴权与幂等</h2>
 *
 * <p>{@code userId} 一律取自 {@code SecurityContext}；幂等键由服务端按入参哈希派生
 * （客户端连点 / 网络重试都会命中同一条未失败任务），避免重复计费。
 */
@Tag(name = "AI 任务中心 · 面试", description = "出题/评分的异步入口（开关关闭时回退同步链路）")
@RestController
@RequestMapping("/api/tasks/interview")
public class InterviewTaskController {

    private static final Logger log = LoggerFactory.getLogger(InterviewTaskController.class);

    /** 与同步接口一致：30 次/分钟/用户，防自动化刷额度 */
    private static final int RATE_LIMIT_PER_MINUTE = 30;
    /** 最大执行次数：出题/评分都是上游不稳定导致的失败，允许重试一次 */
    private static final int MAX_ATTEMPT = 2;

    private final AiTaskService taskService;
    private final ObjectMapper objectMapper;

    private final PerUserRateLimiter aiLimiter = new PerUserRateLimiter(RATE_LIMIT_PER_MINUTE, 60 * 1000L);

    @Value("${app.task-center.enabled:false}")
    private boolean enabled;

    public InterviewTaskController(AiTaskService taskService, ObjectMapper objectMapper) {
        this.taskService = taskService;
        this.objectMapper = objectMapper;
    }

    private static final String DISABLED_MSG = "任务中心未启用";

    @Operation(summary = "创建异步出题任务")
    @PostMapping("/questions")
    public Result<Map<String, Object>> createQuestions(@RequestBody Map<String, Object> request) {
        if (!enabled) {
            return Result.error(503, DISABLED_MSG);
        }
        RequestFieldUtil.TextField rt = RequestFieldUtil.text(request, "resumeText");
        RequestFieldUtil.TextField jd = RequestFieldUtil.text(request, "jobDescription");
        if (rt.hasTypeError()) {
            return Result.error(400, RequestFieldUtil.typeError("resumeText"));
        }
        if (jd.hasTypeError()) {
            return Result.error(400, RequestFieldUtil.typeError("jobDescription"));
        }
        String resumeText = rt.value();
        String jobDescription = jd.value();
        if (resumeText == null || jobDescription == null) {
            return Result.error(400, "简历和岗位描述不能为空");
        }
        if (resumeText.length() > InterviewQuestionTaskHandler.MAX_RESUME_LEN
                || jobDescription.length() > InterviewQuestionTaskHandler.MAX_JD_LEN) {
            return Result.error(400, "简历或岗位描述过长（各最多 "
                    + InterviewQuestionTaskHandler.MAX_RESUME_LEN + " 字符）");
        }
        int count = 5;
        if (request.get("count") instanceof Number n) {
            count = n.intValue();
        }
        count = Math.max(InterviewQuestionTaskHandler.MIN_COUNT,
                Math.min(InterviewQuestionTaskHandler.MAX_COUNT, count));
        String difficulty = request.get("difficulty") == null ? "" : request.get("difficulty").toString();
        String focusCategories = request.get("focusCategories") == null
                ? "" : request.get("focusCategories").toString();
        if (focusCategories.length() > InterviewQuestionTaskHandler.MAX_FOCUS_LEN) {
            return Result.error(400, "聚焦分类过长（最多 "
                    + InterviewQuestionTaskHandler.MAX_FOCUS_LEN + " 字符）");
        }

        String userId = currentUserId();
        requireRateLimit(userId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resumeText", resumeText);
        payload.put("jobDescription", jobDescription);
        payload.put("count", count);
        payload.put("difficulty", difficulty);
        payload.put("focusCategories", focusCategories);
        // 幂等键按内容派生：同样的简历+岗位+数量+自适应参数只跑一次（连点不重复计费）。
        // P1-3（2026-10-31）：显式「重新生成」时掺一个随机量，绕过最长 7 天的复用窗口 ——
        // 否则用户拿着同一份简历永远只能拿到同一批题，且界面没有任何提示。
        // 不传 regenerate 时语义完全不变。
        String key = "q:" + HashUtil.sha256Short(resumeText + "|" + jobDescription + "|" + count
                + "|d=" + difficulty.trim().toUpperCase() + "|f=" + focusCategories.trim()
                + (isRegenerate(request) ? "|r=" + java.util.UUID.randomUUID() : ""));
        return createTask(userId, AiTaskTypes.INTERVIEW_QUESTIONS, key, payload);
    }

    @Operation(summary = "创建异步评分任务（仅纯文本，带附图请走同步接口）")
    @PostMapping("/evaluate")
    public Result<Map<String, Object>> createEvaluate(@RequestBody Map<String, Object> request) {
        if (!enabled) {
            return Result.error(503, DISABLED_MSG);
        }
        if (request.get("imageUrl") != null && !String.valueOf(request.get("imageUrl")).isBlank()) {
            // 附图评分需要服务端换签短时效 URL（10 分钟失效），异步队列等待会让签名过期；
            // 与其在任务里失败，不如让前端直接走同步链路
            return Result.error(400, "带附图的评分请使用同步接口");
        }
        RequestFieldUtil.TextField q = RequestFieldUtil.text(request, "question");
        RequestFieldUtil.TextField a = RequestFieldUtil.text(request, "userAnswer");
        RequestFieldUtil.TextField r = RequestFieldUtil.text(request, "referenceAnswer");
        if (q.hasTypeError() || a.hasTypeError() || r.hasTypeError()) {
            return Result.error(400, "题目、回答、参考答案类型错误，期望字符串");
        }
        if (q.value() == null || a.value() == null) {
            return Result.error(400, "问题和回答不能为空");
        }
        if (q.value().length() > InterviewEvaluateTaskHandler.MAX_QUESTION_LEN
                || a.value().length() > InterviewEvaluateTaskHandler.MAX_ANSWER_LEN
                || (r.value() != null && r.value().length() > InterviewEvaluateTaskHandler.MAX_REFERENCE_LEN)) {
            return Result.error(400, "题目、回答或参考答案过长");
        }

        String userId = currentUserId();
        requireRateLimit(userId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question", q.value());
        payload.put("userAnswer", a.value());
        payload.put("referenceAnswer", r.value() == null ? "" : r.value());
        String key = "e:" + HashUtil.sha256Short(q.value() + "|" + a.value() + "|"
                + (r.value() == null ? "" : r.value()));
        return createTask(userId, AiTaskTypes.INTERVIEW_EVALUATE, key, payload);
    }

    private Result<Map<String, Object>> createTask(String userId, String type, String key,
                                                   Map<String, Object> payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("任务参数序列化失败 type={}", type, e);
            return Result.error(500, "任务参数序列化失败");
        }
        AiTaskEntity task = taskService.create(userId, type, key, json, MAX_ATTEMPT);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", task.getId());
        data.put("status", task.getStatus());
        return Result.success(data);
    }

    /**
     * 是否显式要求「重新生成」（P1-3）。
     *
     * <p>只认布尔 {@code true} 或字符串 {@code "true"}：前端偶尔会把表单值原样带上来，
     * 若把 {@code "false"} 也当真值，幂等就彻底失效了 —— 每次点击都新建任务、重复计费。
     */
    private static boolean isRegenerate(Map<String, Object> request) {
        Object raw = request.get("regenerate");
        if (raw == null) {
            return false;
        }
        return Boolean.TRUE.equals(raw) || "true".equalsIgnoreCase(String.valueOf(raw).trim());
    }

    private void requireRateLimit(String userId) {
        if (!aiLimiter.allow(userId)) {
            throw new RateLimitExceededException("操作过于频繁，请稍后再试");
        }
    }

    /** 当前登录用户 ID（JWT subject） */
    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            throw new IllegalStateException("未认证用户");
        }
        return auth.getPrincipal().toString();
    }
}
