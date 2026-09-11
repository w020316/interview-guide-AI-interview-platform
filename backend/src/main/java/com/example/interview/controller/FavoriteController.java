package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.entity.FavoriteQuestionEntity;
import com.example.interview.entity.InterviewQuestionEntity;
import com.example.interview.entity.InterviewSessionEntity;
import com.example.interview.service.FavoriteService;
import com.example.interview.service.InterviewSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 错题收藏接口
 * - 收藏/取消收藏面试题（快照式）
 * - 查询收藏列表、已收藏题目 ID 集合
 *
 * userId 一律从 JWT token 提取，防止 IDOR 越权。
 */
@Tag(name = "收藏夹", description = "错题/重点题目收藏与回看")
@RestController
@RequestMapping("/api/favorite")
public class FavoriteController {

    @Autowired
    private FavoriteService favoriteService;

    @Autowired
    private InterviewSessionService sessionService;

    /** 从 SecurityContext 获取当前登录用户 ID（JWT subject） */
    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            throw new IllegalStateException("未认证用户");
        }
        return auth.getPrincipal().toString();
    }

    /**
     * 查询我的收藏列表
     * GET /api/favorite/list
     */
    @Operation(summary = "收藏列表")
    @GetMapping("/list")
    public Result<Map<String, Object>> list() {
        String userId = currentUserId();
        List<FavoriteQuestionEntity> items = favoriteService.listByUser(userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", items.size());
        result.put("items", items);
        return Result.success(result);
    }

    /**
     * 查询我已收藏的原题目 ID 集合（用于前端高亮收藏状态）
     * GET /api/favorite/ids
     */
    @Operation(summary = "已收藏题目 ID 集合")
    @GetMapping("/ids")
    public Result<Set<Long>> ids() {
        return Result.success(favoriteService.listFavoriteQuestionIds(currentUserId()));
    }

    /**
     * 切换收藏
     * POST /api/favorite/toggle
     *
     * 两种用法：
     * 1) 通过原题目 ID：Body {"questionId":1,"question":"...","category":"...",...} —— 不存在则新增，存在则取消
     * 2) 通过收藏 ID 直接移除：Body {"favoriteId":10}
     */
    @Operation(summary = "切换（新增/取消）收藏")
    @PostMapping("/toggle")
    public Result<Map<String, Object>> toggle(@RequestBody Map<String, Object> req) {
        String userId = currentUserId();

        Long favoriteId = req.get("favoriteId") != null
                ? Long.valueOf(req.get("favoriteId").toString()) : null;
        Long questionId = req.get("questionId") != null
                ? Long.valueOf(req.get("questionId").toString()) : null;

        if (favoriteId == null && questionId == null) {
            return Result.error(400, "favoriteId 或 questionId 至少需要提供一个");
        }

        // 构建快照（用于新增收藏）
        FavoriteQuestionEntity snapshot = FavoriteQuestionEntity.builder()
                .userId(userId)
                .questionId(questionId)
                .sessionId((String) req.get("sessionId"))
                .question(req.get("question") != null ? req.get("question").toString() : "")
                .category(req.get("category") != null ? req.get("category").toString() : null)
                .difficulty(req.get("difficulty") != null ? req.get("difficulty").toString() : null)
                .referenceAnswer(req.get("referenceAnswer") != null ? req.get("referenceAnswer").toString() : null)
                .userAnswer(req.get("userAnswer") != null ? req.get("userAnswer").toString() : null)
                .evaluationScore(req.get("evaluationScore") != null
                        ? Integer.valueOf(req.get("evaluationScore").toString()) : null)
                .build();

        if (favoriteId == null && (snapshot.getQuestion() == null || snapshot.getQuestion().isBlank())) {
            return Result.error(400, "收藏时题目内容不能为空");
        }

        boolean favorited = favoriteService.toggle(favoriteId, userId, questionId, snapshot);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("favorited", favorited);
        result.put("count", favoriteService.countByUser(userId));
        return Result.success(result);
    }

    /**
     * 从收藏题库发起模拟面试
     * POST /api/favorite/bank/start
     * Body: {"favoriteIds":[1,2,3],"jobDescription":"Java 后端(可选)"}
     *
     * 复用现有会话/答题/评分链路：以所选收藏题目为题目集创建一个新面试会话，
     * 可直接进入答题（不重新调用 AI 生成题目）。
     */
    @Operation(summary = "从收藏题库发起模拟面试")
    @PostMapping("/bank/start")
    public Result<Map<String, Object>> startFromBank(@RequestBody Map<String, Object> req) {
        String userId = currentUserId();

        List<Long> ids = new ArrayList<>();
        Object idsObj = req.get("favoriteIds");
        if (idsObj instanceof List<?> list) {
            for (Object o : list) {
                try {
                    ids.add(Long.valueOf(o.toString()));
                } catch (NumberFormatException ignored) {
                    // 忽略非法 id
                }
            }
        }
        if (ids.isEmpty()) {
            return Result.error(400, "请选择至少一道收藏题目");
        }

        String jobDesc = req.get("jobDescription") == null
                || req.get("jobDescription").toString().isBlank()
                ? "收藏题库" : req.get("jobDescription").toString().trim();

        // 归属校验：只取属于当前用户的收藏，防 IDOR
        List<FavoriteQuestionEntity> favs = favoriteService.listByUser(userId);
        Set<Long> idSet = new HashSet<>(ids);
        List<FavoriteQuestionEntity> chosen = favs.stream()
                .filter(f -> idSet.contains(f.getId()))
                .toList();
        if (chosen.isEmpty()) {
            return Result.error(404, "未找到有效的收藏题目");
        }

        // 创建会话并将收藏题目快照转为会话题目（新会话重新作答，不复用旧回答/分数）
        InterviewSessionEntity session = sessionService.createSession(userId, jobDesc, null);
        List<InterviewQuestionEntity> questions = chosen.stream()
                .map(f -> InterviewQuestionEntity.builder()
                        .sessionId(session.getSessionId())
                        .question(f.getQuestion())
                        .category(f.getCategory())
                        .difficulty(f.getDifficulty())
                        .referenceAnswer(f.getReferenceAnswer())
                        .build())
                .toList();
        List<InterviewQuestionEntity> saved = sessionService.saveQuestions(session.getSessionId(), questions);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", session.getSessionId());
        result.put("questions", saved);
        return Result.success(result);
    }

    /**
     * 手动加题：把自定义题目加入题库（v1.28.0）
     * POST /api/favorite/add
     * Body: {"question":"...","category":"...","difficulty":"...","referenceAnswer":"..."}
     */
    @Operation(summary = "手动添加自定义题目")
    @PostMapping("/add")
    public Result<FavoriteQuestionEntity> add(@RequestBody Map<String, Object> req) {
        String userId = currentUserId();
        String question = req.get("question") != null ? req.get("question").toString() : "";
        if (question.isBlank()) {
            return Result.error(400, "题目内容不能为空");
        }
        String category = req.get("category") != null ? req.get("category").toString() : null;
        String difficulty = req.get("difficulty") != null ? req.get("difficulty").toString() : null;
        String referenceAnswer = req.get("referenceAnswer") != null ? req.get("referenceAnswer").toString() : null;

        FavoriteQuestionEntity saved = favoriteService.addManual(
                userId, question.trim(), category, difficulty, referenceAnswer);
        return Result.success(saved);
    }
}