package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.entity.StoryBankEntity;
import com.example.interview.service.career.StoryBankService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 面试故事库接口（v1.36.0）
 *
 * <p>落地「VibeCoding大赏｜求职Skill」的 interview-story-bank / interview-practice 思路：
 * <ul>
 *   <li>{@code GET  /api/story-bank} —— 故事列表</li>
 *   <li>{@code POST /api/story-bank/extract} —— AI 提炼 STAR 故事候选（不入库）</li>
 *   <li>{@code POST /api/story-bank} —— 保存故事（用户确认后入库）</li>
 *   <li>{@code POST /api/story-bank/{id}/check} —— 六项质检（结构/证据/贴合岗位/废话/风险/追问）</li>
 *   <li>{@code POST /api/story-bank/{id}/followup} —— 追问链（先回答，再追问，再复盘）</li>
 *   <li>{@code DELETE /api/story-bank/{id}} —— 删除</li>
 * </ul>
 *
 * userId 一律从 JWT 提取，不接受请求体传入（防 IDOR）。
 */
@Tag(name = "面试故事库", description = "STAR 故事提炼、质检与追问练习")
@RestController
@RequestMapping("/api/story-bank")
public class StoryBankController {

    private static final Logger log = LoggerFactory.getLogger(StoryBankController.class);

    /** 请求体文本上限（与 Service 侧截断一致） */
    private static final int MAX_TEXT_LEN = 6000;

    @Autowired
    private StoryBankService storyBankService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 从 SecurityContext 获取当前登录用户 ID（JWT subject） */
    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            throw new IllegalStateException("未认证用户");
        }
        return auth.getPrincipal().toString();
    }

    /** 故事列表 */
    @Operation(summary = "我的面试故事库列表")
    @GetMapping
    public Result<List<StoryBankEntity>> list() {
        return Result.success(storyBankService.list(currentUserId()));
    }

    /**
     * AI 提炼 STAR 故事候选（不入库）
     * Body: {"narrative":"我的经历……","targetTrack":"Java 后端(可选)"}
     */
    @Operation(summary = "从经历自述提炼 STAR 故事候选")
    @PostMapping("/extract")
    public Result<Map<String, Object>> extract(@RequestBody Map<String, Object> req) {
        String userId = currentUserId();
        String narrative = str(req.get("narrative"));
        if (narrative == null || narrative.isBlank()) {
            return Result.error(400, "请先描述你的真实经历（narrative 不能为空）");
        }
        if (narrative.length() > MAX_TEXT_LEN) {
            return Result.error(400, "经历描述过长，请精简后再试（建议 3000 字以内）");
        }
        String targetTrack = strOrEmpty(req.get("targetTrack"));

        String json = storyBankService.extract(userId, narrative, targetTrack);
        return Result.success(parseToMap(json, "extract"));
    }

    /** 保存故事（用户确认后入库）。Body: 故事字段（title 必填） */
    @Operation(summary = "保存故事到我的故事库")
    @PostMapping
    public Result<StoryBankEntity> save(@RequestBody StoryBankEntity story) {
        String userId = currentUserId();
        return Result.success(storyBankService.save(userId, story));
    }

    /**
     * 六项质检：对该故事的模拟口述回答做质检。
     * Body: {"answer":"我的口述回答"}
     */
    @Operation(summary = "六项质检（结构/证据/贴合岗位/废话/风险/追问）")
    @PostMapping("/{id}/check")
    public Result<Map<String, Object>> check(@PathVariable Long id, @RequestBody Map<String, Object> req) {
        String userId = currentUserId();
        String answer = str(req.get("answer"));
        if (answer == null || answer.isBlank()) {
            return Result.error(400, "请先输入你的模拟回答（answer 不能为空）");
        }
        StoryBankEntity story = storyBankService.findOwned(userId, id);
        if (story == null) {
            return Result.error(404, "故事不存在或无权操作");
        }

        String json = storyBankService.check(story, answer);
        // 质检结果随故事持久化，作为「最近一次质检」快照（失败不阻断返回）
        try {
            story.setCheckResult(json);
            storyBankService.updateCheckResult(userId, id, json);
        } catch (Exception e) {
            log.warn("质检结果保存失败 storyId={} err={}", id, e.getMessage());
        }
        return Result.success(parseToMap(json, "check"));
    }

    /**
     * 追问链：「先回答，再追问，再复盘」。Body: {"answer":"我的口述回答"}
     */
    @Operation(summary = "生成追问链（3 条，由浅入深）")
    @PostMapping("/{id}/followup")
    public Result<Map<String, Object>> followup(@PathVariable Long id, @RequestBody Map<String, Object> req) {
        String userId = currentUserId();
        String answer = str(req.get("answer"));
        if (answer == null || answer.isBlank()) {
            return Result.error(400, "请先输入你的模拟回答（answer 不能为空）");
        }
        StoryBankEntity story = storyBankService.findOwned(userId, id);
        if (story == null) {
            return Result.error(404, "故事不存在或无权操作");
        }

        String json = storyBankService.followUp(story, answer);
        return Result.success(parseToMap(json, "followup"));
    }

    /** 删除故事 */
    @Operation(summary = "删除故事")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        storyBankService.delete(currentUserId(), id);
        return Result.success(null);
    }

    /** JSON 字符串 → Map；解析失败返回带 raw 的兜底结构（保证前端不白屏） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseToMap(String json, String context) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("面试故事库结果解析失败 context={} err={}", context, e.getMessage());
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("raw", json);
            return fallback;
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    /** 可选文本参数归一：null 统一为 ""（避免 "null" 字面量混入 prompt） */
    private static String strOrEmpty(Object v) {
        return v == null ? "" : v.toString();
    }
}
