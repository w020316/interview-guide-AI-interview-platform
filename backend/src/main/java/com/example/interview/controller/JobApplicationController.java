package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.entity.JobApplicationEntity;
import com.example.interview.entity.JobFavoriteEntity;
import com.example.interview.entity.JobPostingEntity;
import com.example.interview.service.JobFavoriteService;
import com.example.interview.service.career.TailoredResumeService;
import com.example.interview.service.job.JobAgentService;
import com.example.interview.service.job.JobApplicationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 投递台账接口（v1.35.0）
 *
 * <p>落地抖音「求职 agent 大更新 / BossHunter」的投递闭环：本地台账 + 人工确认投递 +
 * 回复监测 + 定制简历。**合规边界**：平台不代替用户登录招聘平台、不自动投递，
 * 只提供台账与提醒，实际投递由用户点击 applyUrl 自行完成。
 *
 * userId 一律从 JWT 提取，所有按 ID 的操作都做归属校验（防 IDOR）。
 */
@Tag(name = "投递台账", description = "投递计划、状态推进与回复监测")
@RestController
@RequestMapping("/api/application")
public class JobApplicationController {

    private static final Logger log = LoggerFactory.getLogger(JobApplicationController.class);

    @Autowired
    private JobApplicationService applicationService;

    @Autowired
    private JobAgentService jobAgentService;

    @Autowired
    private JobFavoriteService favoriteService;

    @Autowired
    private TailoredResumeService tailoredResumeService;

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
     * 我的投递列表
     * GET /api/application/list
     */
    @Operation(summary = "投递列表")
    @GetMapping("/list")
    public Result<Map<String, Object>> list() {
        String userId = currentUserId();
        List<JobApplicationEntity> items = applicationService.listByUser(userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", items.size());
        result.put("items", items);
        result.put("statusLabels", JobApplicationService.STATUS_LABELS);
        return Result.success(result);
    }

    /**
     * 投递看板（分列计数 + 转化漏斗 + 待跟进提醒）
     * GET /api/application/board
     */
    @Operation(summary = "投递看板与回复监测")
    @GetMapping("/board")
    public Result<Map<String, Object>> board() {
        return Result.success(applicationService.board(currentUserId()));
    }

    /**
     * 加入投递台账（创建 PLANNED 草稿，幂等）
     * POST /api/application/draft
     * Body: {"jobId":1} 或 {"favoriteId":2}，可选 {"note":"内推/官网"}
     *
     * 二选一：优先 jobId（岗位库岗位），否则 favoriteId（岗位收藏快照）。
     */
    @Operation(summary = "加入投递台账")
    @PostMapping("/draft")
    public Result<JobApplicationEntity> draft(@RequestBody Map<String, Object> req) {
        String userId = currentUserId();
        String note = req.get("note") == null ? null : req.get("note").toString();

        Long jobId = longVal(req.get("jobId"));
        if (jobId != null) {
            JobPostingEntity job = jobAgentService.findById(jobId);
            if (job == null) {
                return Result.error(404, "未找到该岗位，可能已下架");
            }
            return Result.success(applicationService.addFromJob(userId, job, note));
        }

        Long favoriteId = longVal(req.get("favoriteId"));
        if (favoriteId != null) {
            // 归属校验：只能从自己的收藏转入台账
            JobFavoriteEntity favorite = favoriteService.listByUser(userId).stream()
                    .filter(f -> favoriteId.equals(f.getId()))
                    .findFirst()
                    .orElse(null);
            if (favorite == null) {
                return Result.error(404, "未找到该收藏记录");
            }
            return Result.success(applicationService.addFromFavorite(userId, favorite, note));
        }

        return Result.error(400, "jobId 或 favoriteId 至少需要提供一个");
    }

    /**
     * 人工确认投递：PLANNED → APPLIED
     * POST /api/application/{id}/confirm
     *
     * 语义为「用户已自行前往 applyUrl 完成投递」，平台不代替投递。
     */
    @Operation(summary = "确认已投递")
    @PostMapping("/{id}/confirm")
    public Result<JobApplicationEntity> confirm(@PathVariable Long id) {
        JobApplicationEntity updated = applicationService.confirmApply(currentUserId(), id);
        if (updated == null) {
            return Result.error(404, "未找到该投递记录");
        }
        return Result.success(updated);
    }

    /**
     * 记录回复 / 推进状态（回复监测）
     * POST /api/application/{id}/status
     * Body: {"status":"REPLIED","note":"HR 约面","nextActionAt":"2026-10-01T10:00"}
     *
     * status 取值见 {@link JobApplicationService#ALL_STATUSES}。
     */
    @Operation(summary = "推进投递状态")
    @PostMapping("/{id}/status")
    public Result<JobApplicationEntity> updateStatus(@PathVariable Long id,
                                                     @RequestBody Map<String, Object> req) {
        String status = req.get("status") == null ? null : req.get("status").toString();
        if (status == null || status.isBlank()) {
            return Result.error(400, "status 不能为空");
        }
        String note = req.get("note") == null ? null : req.get("note").toString();
        LocalDateTime nextActionAt = parseDateTime(req.get("nextActionAt"));

        JobApplicationEntity updated =
                applicationService.updateStatus(currentUserId(), id, status, note, nextActionAt);
        if (updated == null) {
            return Result.error(400, "状态非法或投递记录不存在，合法状态：" + JobApplicationService.ALL_STATUSES);
        }
        return Result.success(updated);
    }

    /**
     * 生成并保存针对该岗位的定制简历要点
     * POST /api/application/{id}/tailor
     * Body: {"resumeText":"我的简历要点","jobDetail":"可选，补充 JD"}
     *
     * 生成结果持久化到台账记录，便于投递时直接取用。
     */
    @Operation(summary = "生成定制简历要点")
    @PostMapping("/{id}/tailor")
    public Result<Map<String, Object>> tailor(@PathVariable Long id,
                                              @RequestBody Map<String, Object> req) {
        String userId = currentUserId();
        JobApplicationEntity app = applicationService.findOwned(userId, id);
        if (app == null) {
            return Result.error(404, "未找到该投递记录");
        }
        String resumeText = req.get("resumeText") == null ? "" : req.get("resumeText").toString();
        if (resumeText.isBlank()) {
            return Result.error(400, "请先提供简历要点（resumeText 不能为空）");
        }
        String jobDetail = req.get("jobDetail") == null ? "" : req.get("jobDetail").toString();

        String json = tailoredResumeService.tailor(resumeText, app.getTitle(), jobDetail);
        applicationService.saveTailoredResume(userId, id, json);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applicationId", id);
        result.put("tailoredResume", parseToMap(json));
        return Result.success(result);
    }

    /**
     * 从台账移除（放弃追踪）
     * DELETE /api/application/{id}
     */
    @Operation(summary = "移除投递记录")
    @DeleteMapping("/{id}")
    public Result<Map<String, Object>> remove(@PathVariable Long id) {
        boolean removed = applicationService.remove(currentUserId(), id);
        if (!removed) {
            return Result.error(404, "未找到该投递记录");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("removed", true);
        return Result.success(result);
    }

    /** 可选时间参数解析：支持 ISO-8601（如 2026-10-01T10:00）；非法值按 null 处理 */
    private LocalDateTime parseDateTime(Object v) {
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return LocalDateTime.parse(s);
        } catch (DateTimeParseException e) {
            log.warn("nextActionAt 格式非法，已忽略：{}", s);
            return null;
        }
    }

    private static Long longVal(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return Long.valueOf(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseToMap(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("定制简历结果解析失败：{}", e.getMessage());
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("raw", json);
            return fallback;
        }
    }
}
