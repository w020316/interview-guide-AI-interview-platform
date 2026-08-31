package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.entity.FavoriteQuestionEntity;
import com.example.interview.service.FavoriteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

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
}