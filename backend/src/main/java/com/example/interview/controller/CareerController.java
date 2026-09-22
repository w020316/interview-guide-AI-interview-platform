package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.service.career.CareerProfileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 求职 Skill 接口（v1.35.0）
 *
 * <p>落地抖音「VibeCoding大赏｜求职Skill」的方法论：
 * <ul>
 *   <li>{@code POST /api/career/mine} —— 职业资产四层挖掘（证据→行为→能力→可投岗位信号）</li>
 *   <li>{@code POST /api/career/plan} —— 岗位节奏计划（为什么适合/差距/30天case/赛道）</li>
 * </ul>
 *
 * userId 一律从 JWT 提取，不接受请求体传入（防 IDOR）。
 */
@Tag(name = "求职 Skill", description = "职业资产挖掘与求职节奏规划")
@RestController
@RequestMapping("/api/career")
public class CareerController {

    private static final Logger log = LoggerFactory.getLogger(CareerController.class);

    /** 用户自述经历的请求体上限（与 Service 侧截断一致，避免超大 body 直灌模型） */
    private static final int MAX_NARRATIVE_LEN = 3000;

    @Autowired
    private CareerProfileService careerProfileService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 从 SecurityContext 获取当前登录用户 ID（JWT subject） */
    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            throw new IllegalStateException("未认证用户");
        }
        return auth.getPrincipal().toString();
    }

    /**
     * 职业资产四层挖掘
     * POST /api/career/mine
     * Body: {"narrative":"我的经历……","targetTrack":"Java 后端(可选)"}
     *
     * 返回解析后的对象（positioning/assets/strengthTags/blindSpots/nextSteps），
     * 前端可直接渲染；模型输出非法 JSON 时由服务层兜底为空结构，接口仍返回 200。
     */
    @Operation(summary = "职业资产挖掘（证据→行为→能力→岗位信号）")
    @PostMapping("/mine")
    public Result<Map<String, Object>> mine(@RequestBody Map<String, Object> req) {
        String userId = currentUserId();
        String narrative = str(req.get("narrative"));
        if (narrative == null || narrative.isBlank()) {
            return Result.error(400, "请先描述你的真实经历（narrative 不能为空）");
        }
        if (narrative.length() > MAX_NARRATIVE_LEN * 2) {
            return Result.error(400, "经历描述过长，请精简后再试（建议 3000 字以内）");
        }
        String targetTrack = str(req.get("targetTrack"));

        String json = careerProfileService.mine(userId, narrative, targetTrack);
        return Result.success(parseToMap(json, "mine"));
    }

    /**
     * 岗位节奏计划
     * POST /api/career/plan
     * Body: {"targetJob":"Java 后端（可含 JD）","resumeText":"简历要点","mineSummary":"可选，挖掘摘要"}
     */
    @Operation(summary = "岗位节奏计划（why-fit / gap / 30天case / 赛道）")
    @PostMapping("/plan")
    public Result<Map<String, Object>> plan(@RequestBody Map<String, Object> req) {
        String userId = currentUserId();
        String targetJob = str(req.get("targetJob"));
        if (targetJob == null || targetJob.isBlank()) {
            return Result.error(400, "请提供目标岗位（targetJob 不能为空）");
        }
        String resumeText = strOrEmpty(req.get("resumeText"));
        String mineSummary = strOrEmpty(req.get("mineSummary"));

        String json = careerProfileService.plan(userId, targetJob, resumeText, mineSummary);
        return Result.success(parseToMap(json, "plan"));
    }

    /** JSON 字符串 → Map；解析失败返回带 raw 的兜底结构（保证前端不白屏） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseToMap(String json, String context) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("求职 Skill 结果解析失败 context={} err={}", context, e.getMessage());
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("raw", json);
            return fallback;
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    /**
     * 可选文本参数归一：null 统一为 ""。
     *
     * <p>避免把 null 传入服务层参与提示词拼接（历史上出现过 "null" 字面量混入 prompt 的问题），
     * 同时让「未提供」与「提供空串」在服务层语义一致。
     */
    private static String strOrEmpty(Object v) {
        return v == null ? "" : v.toString();
    }
}
