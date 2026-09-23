package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.entity.InterviewQuestionEntity;
import com.example.interview.entity.InterviewSessionEntity;
import com.example.interview.service.InterviewSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 面试会话 CRUD 接口
 * - 创建/查询/完成会话
 * - 题目保存、用户回答保存
 * - userId 从 JWT token 中提取，防止 IDOR 越权
 */
@RestController
@RequestMapping("/api/session")
public class InterviewSessionController {

    @Autowired
    private InterviewSessionService sessionService;

    @Autowired
    private ObjectMapper objectMapper;

    /** 从 SecurityContext 获取当前登录用户 ID（JWT subject） */
    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            throw new IllegalStateException("未认证用户");
        }
        return auth.getPrincipal().toString();
    }

    /** 安全读取字符串字段：缺失/null 返回空串，避免 Map<String,Object> 取值时的类型转换异常 */
    private static String text(Map<String, Object> src, String key) {
        Object v = src.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    /** 安全读取可空字符串字段：缺失/null 返回 null（与旧实现语义一致） */
    private static String nullableText(Map<String, Object> src, String key) {
        Object v = src.get(key);
        return v == null ? null : String.valueOf(v);
    }

    /**
     * 把 keyPoints 归一化为文本存库。
     * AI 出题返回的是字符串数组，而实体列是 String，这里统一序列化为 JSON 文本；
     * 若模型偶尔返回字符串则原样保存；序列化失败时退化为按行拼接，绝不因该字段导致整批保存失败。
     */
    private String toKeyPointsText(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s.isBlank() ? null : s;
        }
        if (value instanceof Collection<?> c) {
            if (c.isEmpty()) {
                return null;
            }
            try {
                return objectMapper.writeValueAsString(c);
            } catch (Exception e) {
                return c.stream().map(String::valueOf).collect(Collectors.joining("\n"));
            }
        }
        return String.valueOf(value);
    }

    /**
     * 创建面试会话
     * POST /api/session/create
     * Body: {"jobDescription":"Java 后端","resumeId":1}
     * userId 自动从 token 提取，不接受客户端传入
     */
    @PostMapping("/create")
    public Result<InterviewSessionEntity> createSession(@RequestBody Map<String, Object> req) {
        String userId = currentUserId();
        String jobDesc = (String) req.get("jobDescription");
        Long resumeId = req.get("resumeId") != null
                ? Long.valueOf(req.get("resumeId").toString()) : null;

        if (jobDesc == null || jobDesc.isBlank()) {
            return Result.error(400, "jobDescription 不能为空");
        }
        return Result.success(sessionService.createSession(userId, jobDesc, resumeId));
    }

    /**
     * 查询当前用户历史会话列表
     * GET /api/session/list
     * userId 自动从 token 提取
     */
    @GetMapping("/list")
    public Result<List<InterviewSessionEntity>> listSessions() {
        return Result.success(sessionService.listByUser(currentUserId()));
    }

    /**
     * 获取会话详情（仅限本人会话）
     * GET /api/session/{sessionId}
     */
    @GetMapping("/{sessionId}")
    public Result<InterviewSessionEntity> getSession(@PathVariable String sessionId) {
        // service 在会话不存在时抛 IllegalArgumentException，由全局异常处理器统一返回 400
        InterviewSessionEntity session = sessionService.getBySessionId(sessionId);
        if (!currentUserId().equals(session.getUserId())) {
            return Result.error(403, "无权访问该会话");
        }
        return Result.success(session);
    }

    /**
     * 结束面试会话（仅限本人会话）
     * PUT /api/session/{sessionId}/finish
     */
    @PutMapping("/{sessionId}/finish")
    public Result<InterviewSessionEntity> finishSession(@PathVariable String sessionId) {
        // controller 层校验用于返回友好 403；service 层再做一次归属校验（纵深防御，B-17）
        InterviewSessionEntity session = sessionService.getBySessionId(sessionId);
        if (!currentUserId().equals(session.getUserId())) {
            return Result.error(403, "无权操作该会话");
        }
        return Result.success(sessionService.finishSession(sessionId, currentUserId()));
    }

    /**
     * 查询会话下的所有题目（仅限本人会话）
     * GET /api/session/{sessionId}/questions
     */
    @GetMapping("/{sessionId}/questions")
    public Result<List<InterviewQuestionEntity>> listQuestions(@PathVariable String sessionId) {
        InterviewSessionEntity session = sessionService.getBySessionId(sessionId);
        if (!currentUserId().equals(session.getUserId())) {
            return Result.error(403, "无权访问该会话");
        }
        return Result.success(sessionService.listQuestions(sessionId));
    }

    /**
     * 批量保存 AI 生成的面试题到会话（仅限本人会话）
     * POST /api/session/{sessionId}/questions
     * Body: [{"question":"...","category":"...","difficulty":"...","keyPoints":["..."],"referenceAnswer":"..."}, ...]
     *
     * 用于 startInterview 成功后持久化题目，支持刷新页面/历史回顾
     *
     * 注意：请求体声明为 Map&lt;String, Object&gt; 而非 Map&lt;String, String&gt;。
     * AI 出题返回的 keyPoints 是字符串数组，若声明成 Map&lt;String, String&gt;，
     * Jackson 会在反序列化阶段直接抛错（整批题目 400 落不了库，历史/错题本/趋势全空）。
     */
    @PostMapping("/{sessionId}/questions")
    public Result<List<InterviewQuestionEntity>> saveQuestions(@PathVariable String sessionId,
                                                                @RequestBody List<Map<String, Object>> questions) {
        InterviewSessionEntity session = sessionService.getBySessionId(sessionId);
        if (!currentUserId().equals(session.getUserId())) {
            return Result.error(403, "无权操作该会话");
        }
        if (questions == null || questions.isEmpty()) {
            return Result.error(400, "题目列表不能为空");
        }
        // P2-09：批量与字段长度上限，防单请求灌库与数据库膨胀
        if (questions.size() > 50) {
            return Result.error(400, "单次最多保存 50 道题");
        }
        for (Map<String, Object> q : questions) {
            if (text(q, "question").length() > 2000) {
                return Result.error(400, "单题内容不能超过 2000 字");
            }
            String ref = nullableText(q, "referenceAnswer");
            if (ref != null && ref.length() > 4000) {
                return Result.error(400, "参考答案不能超过 4000 字");
            }
        }

        List<InterviewQuestionEntity> entities = questions.stream().map(q ->
                InterviewQuestionEntity.builder()
                        .sessionId(sessionId)
                        .question(text(q, "question"))
                        .category(text(q, "category"))
                        .difficulty(text(q, "difficulty"))
                        .keyPoints(toKeyPointsText(q.get("keyPoints")))
                        .referenceAnswer(nullableText(q, "referenceAnswer"))
                        .build()
        ).toList();

        // 过滤掉 question 为空的无效项
        entities = entities.stream().filter(e -> !e.getQuestion().isBlank()).toList();
        if (entities.isEmpty()) {
            return Result.error(400, "题目内容不能为空");
        }

        return Result.success(sessionService.saveQuestions(sessionId, entities, currentUserId()));
    }

    /**
     * 提交用户回答及评估结果
     * POST /api/session/answer
     * Body: {"questionId":1,"userAnswer":"...","evaluationScore":80}
     */
    @PostMapping("/answer")
    public Result<InterviewQuestionEntity> saveAnswer(@RequestBody Map<String, Object> req) {
        Object qid = req.get("questionId");
        if (qid == null) {
            return Result.error(400, "questionId 不能为空");
        }
        Long questionId = Long.valueOf(qid.toString());
        String userAnswer = (String) req.get("userAnswer");
        Integer score = req.get("evaluationScore") != null
                ? Integer.valueOf(req.get("evaluationScore").toString()) : null;

        if (userAnswer == null || userAnswer.isBlank()) {
            return Result.error(400, "userAnswer 不能为空");
        }
        return Result.success(sessionService.saveAnswer(questionId, userAnswer, score, currentUserId()));
    }
}
