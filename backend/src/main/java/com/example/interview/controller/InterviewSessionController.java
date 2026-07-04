package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.entity.InterviewQuestionEntity;
import com.example.interview.entity.InterviewSessionEntity;
import com.example.interview.service.InterviewSessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 面试会话 CRUD 接口
 * - 创建/查询/完成会话
 * - 题目保存、用户回答保存
 */
@RestController
@RequestMapping("/api/session")
@CrossOrigin(origins = "*")
public class InterviewSessionController {

    @Autowired
    private InterviewSessionService sessionService;

    /**
     * 创建面试会话
     * POST /api/session/create
     * Body: {"userId":"u1","jobDescription":"Java 后端","resumeId":1}
     */
    @PostMapping("/create")
    public Result<InterviewSessionEntity> createSession(@RequestBody Map<String, Object> req) {
        String userId = (String) req.get("userId");
        String jobDesc = (String) req.get("jobDescription");
        Long resumeId = req.get("resumeId") != null
                ? Long.valueOf(req.get("resumeId").toString()) : null;

        if (userId == null || jobDesc == null) {
            return Result.error(400, "userId 和 jobDescription 不能为空");
        }
        return Result.success(sessionService.createSession(userId, jobDesc, resumeId));
    }

    /**
     * 查询用户历史会话列表
     * GET /api/session/list?userId=u1
     */
    @GetMapping("/list")
    public Result<List<InterviewSessionEntity>> listSessions(@RequestParam String userId) {
        if (userId == null || userId.isBlank()) {
            return Result.error(400, "userId 不能为空");
        }
        return Result.success(sessionService.listByUser(userId));
    }

    /**
     * 获取会话详情
     * GET /api/session/{sessionId}
     */
    @GetMapping("/{sessionId}")
    public Result<InterviewSessionEntity> getSession(@PathVariable String sessionId) {
        return Result.success(sessionService.getBySessionId(sessionId));
    }

    /**
     * 结束面试会话
     * PUT /api/session/{sessionId}/finish
     */
    @PutMapping("/{sessionId}/finish")
    public Result<InterviewSessionEntity> finishSession(@PathVariable String sessionId) {
        return Result.success(sessionService.finishSession(sessionId));
    }

    /**
     * 查询会话下的所有题目
     * GET /api/session/{sessionId}/questions
     */
    @GetMapping("/{sessionId}/questions")
    public Result<List<InterviewQuestionEntity>> listQuestions(@PathVariable String sessionId) {
        return Result.success(sessionService.listQuestions(sessionId));
    }

    /**
     * 提交用户回答及评估结果
     * POST /api/session/answer
     * Body: {"questionId":1,"userAnswer":"...","evaluationScore":80}
     */
    @PostMapping("/answer")
    public Result<InterviewQuestionEntity> saveAnswer(@RequestBody Map<String, Object> req) {
        Long questionId = Long.valueOf(req.get("questionId").toString());
        String userAnswer = (String) req.get("userAnswer");
        Integer score = req.get("evaluationScore") != null
                ? Integer.valueOf(req.get("evaluationScore").toString()) : null;

        if (userAnswer == null) {
            return Result.error(400, "userAnswer 不能为空");
        }
        return Result.success(sessionService.saveAnswer(questionId, userAnswer, score));
    }
}
