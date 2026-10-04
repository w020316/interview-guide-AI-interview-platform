package com.example.interview.controller;

import com.example.interview.common.ResourceNotFoundException;
import com.example.interview.common.Result;
import com.example.interview.entity.JobApplicationEntity;
import com.example.interview.entity.JobFavoriteEntity;
import com.example.interview.entity.JobPostingEntity;
import com.example.interview.service.JobFavoriteService;
import com.example.interview.service.career.TailoredResumeService;
import com.example.interview.service.job.ApplicationImportService;
import com.example.interview.service.job.ApplicationTimelineService;
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
import java.util.ArrayList;
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

    @Autowired
    private ApplicationImportService applicationImportService;

    @Autowired
    private ApplicationTimelineService applicationTimelineService;

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
     * 投递 ↔ 面试时序视图（v1.61.0，竞品清单 #4）
     * GET /api/application/timeline
     *
     * <p>把「投递」与「面试日程」两类真实时间点合并成一条时间线，回答
     * 「投了之后多久接到面试」。**只输出数据库里真实存在的时间**——未填投出时间的草稿
     * 不在时间线上，未关联投递的日程如实标为「未关联」，不硬塞关联、不推算时间。
     */
    @Operation(summary = "投递↔面试时序视图")
    @GetMapping("/timeline")
    public Result<Map<String, Object>> timeline() {
        return Result.success(applicationTimelineService.timeline(currentUserId()));
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
            // v1.48.0（第六轮 P1-05）：统一「资源不存在」口径为 HTTP 404 + code 404
            throw new ResourceNotFoundException("未找到该投递记录");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("removed", true);
        return Result.success(result);
    }

    /**
     * 批量导入投递台账（第三批 F）
     * POST /api/application/import
     * Body: {"source":"csv|tsv|paste","rows":[{...}],"dryRun":true}
     *
     * <p>dryRun=true 仅预览零写库；dryRun=false 单事务原子写入。
     * <b>合规（R1）</b>：导入的行只进本地台账，不触发任何投递动作。
     * 单次 ≤200 行；存在致命错误行时返回 400 且零写库。
     */
    @Operation(summary = "批量导入投递台账（原子写入）")
    @PostMapping("/import")
    public Result<Map<String, Object>> importApplications(@RequestBody Map<String, Object> req) {
        String userId = currentUserId();
        String source = req.get("source") == null ? "paste" : req.get("source").toString();
        boolean dryRun = Boolean.TRUE.equals(req.get("dryRun"));

        Object rowsObj = req.get("rows");
        if (!(rowsObj instanceof List<?> rawList) || rawList.isEmpty()) {
            return Result.error(400, "rows 不能为空");
        }
        if (rawList.size() > ApplicationImportService.MAX_ROWS) {
            return Result.error(400, "单次最多导入 " + ApplicationImportService.MAX_ROWS + " 行");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object o : rawList) {
            Map<String, Object> row = new LinkedHashMap<>();
            if (o instanceof Map<?, ?> m) {
                m.forEach((k, v) -> row.put(String.valueOf(k), v));
            }
            rows.add(row);
        }

        Map<String, Object> result = applicationImportService.run(userId, source, rows, dryRun);
        // apply（dryRun=false）遇致命错误行：必须回 **HTTP 400**，而非 200 + applied:false 的「假绿灯」。
        // 「输入缺失/解析失败 ≠ 通过」是本项目铁律，且 200 无法被线上复验为「被拒绝」。
        // 沿用既有 400 错误契约：抛 IllegalArgumentException → GlobalExceptionHandler 映射为
        // HTTP 400 + {code:400,message}（与 /api/me/import 的错误语义一致）。
        // 服务层已在任何写库动作前拦截，保证零写库；**dryRun 仍返回 200**（预览需能返回
        // canApply:false + 致命明细，否则「预览确认」交互作废）。
        if (!dryRun && Boolean.FALSE.equals(result.get("canApply"))) {
            Object msg = result.get("message");
            throw new IllegalArgumentException(msg == null ? "存在致命错误行，已取消导入" : msg.toString());
        }
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
