package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.entity.JobFavoriteEntity;
import com.example.interview.entity.JobPostingEntity;
import com.example.interview.service.JobFavoriteService;
import com.example.interview.service.job.JobAgentService;
import com.example.interview.service.job.JobMatchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 招聘信息智能体接口
 * - 岗位列表：关键词搜索 + 行业/职位类型/地点/招聘类型/来源多条件筛选
 * - 岗位详情 / 筛选元数据 / 手动刷新
 * - 岗位收藏（快照式）+ 截止日期临近提醒数据
 *
 * 遵循全局 JWT 认证（SecurityConfig anyRequest().authenticated()），userId 不入库（岗位为公共数据）。
 */
@Tag(name = "招聘信息", description = "秋招/社招岗位聚合搜索与筛选")
@RestController
@RequestMapping("/api/jobs")
public class JobAgentController {

    private final JobAgentService jobAgentService;
    private final JobFavoriteService jobFavoriteService;
    private final JobMatchService jobMatchService;

    public JobAgentController(JobAgentService jobAgentService, JobFavoriteService jobFavoriteService,
                              JobMatchService jobMatchService) {
        this.jobAgentService = jobAgentService;
        this.jobFavoriteService = jobFavoriteService;
        this.jobMatchService = jobMatchService;
    }

    /** 从 SecurityContext 获取当前登录用户 ID（JWT subject） */
    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            throw new IllegalStateException("未认证用户");
        }
        return auth.getPrincipal().toString();
    }

    /**
     * 岗位列表（多条件筛选）
     * GET /api/jobs?keyword=java&industry=互联网&jobType=技术&location=深圳&recruitType=AUTUMN&source=内置精选&degree=本科及以上&experience=1-3 年&page=0&size=10
     */
    @Operation(summary = "岗位列表（多条件筛选搜索）")
    @GetMapping
    public Result<Map<String, Object>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String industry,
            @RequestParam(required = false) String jobType,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) String recruitType,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String degree,
            @RequestParam(required = false) String experience,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "10") int size) {
        Page<JobPostingEntity> result = jobAgentService.search(
                keyword, industry, jobType, location, recruitType, source, degree, experience, page, size);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", result.getTotalElements());
        data.put("page", result.getNumber());
        data.put("size", result.getSize());
        data.put("items", result.getContent());
        return Result.success(data);
    }

    /**
     * 岗位详情
     * GET /api/jobs/{id}
     */
    @Operation(summary = "岗位详情")
    @GetMapping("/{id}")
    public Result<JobPostingEntity> detail(@PathVariable Long id) {
        JobPostingEntity job = jobAgentService.findById(id);
        if (job == null) {
            return Result.error(404, "岗位不存在或已下架");
        }
        return Result.success(job);
    }

    /**
     * 筛选面板元数据：行业/职位类型/来源/各招聘类型数量/最近更新时间
     * GET /api/jobs/meta
     */
    @Operation(summary = "筛选面板元数据")
    @GetMapping("/meta")
    public Result<Map<String, Object>> meta() {
        return Result.success(jobAgentService.meta());
    }

    @Operation(summary = "简历匹配推荐（岗位吻合度打分）")
    @PostMapping("/match")
    public Result<Map<String, Object>> resumeMatch(@RequestBody Map<String, Object> req) {
        String resumeText = req.get("resumeText") != null ? req.get("resumeText").toString() : "";
        if (resumeText.isBlank()) {
            return Result.error(400, "请提供简历内容");
        }
        int limit = req.get("limit") != null ? Integer.parseInt(req.get("limit").toString()) : 10;

        var matched = jobMatchService.match(resumeText, jobAgentService.activeJobs(), limit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", matched.size());
        result.put("topSkills", jobMatchService.extractSkills(resumeText));
        List<Map<String, Object>> items = matched.stream().map(m -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("job", m.job());
            row.put("matchScore", m.matchScore());
            row.put("matchedSkills", m.matchedSkills());
            return row;
        }).toList();
        result.put("items", items);
        return Result.success(result);
    }

    /**
     * 手动刷新（也可由定时任务自动执行）
     * POST /api/jobs/refresh
     * 已有刷新任务执行中时返回 429，避免并发刷新撞库
     */
    @Operation(summary = "手动刷新岗位数据")
    @PostMapping("/refresh")
    public Result<JobAgentService.RefreshResult> refresh() {
        JobAgentService.RefreshResult result = jobAgentService.refresh();
        if (result == null) {
            return Result.error(429, "岗位数据正在刷新中，请稍后再试");
        }
        return Result.success(result);
    }

    // ── 岗位收藏（v1.23.3）──

    /**
     * 查询我的岗位收藏列表（快照式，含截止日倒计时与提醒标记）
     * GET /api/jobs/favorite
     *
     * 提醒规则：deadline 7 天内 favorited 项附 remind=true，过期附 expired=true（前端据此渲染提醒横幅）
     */
    @Operation(summary = "我的岗位收藏列表（含截止日提醒）")
    @GetMapping("/favorite")
    public Result<Map<String, Object>> favoriteList() {
        List<JobFavoriteEntity> items = jobFavoriteService.listByUser(currentUserId());
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> rows = items.stream().map(f -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", f.getId());
            row.put("jobId", f.getJobId());
            row.put("title", f.getTitle());
            row.put("companyName", f.getCompanyName());
            row.put("platform", f.getPlatform());
            row.put("location", f.getLocation());
            row.put("salary", f.getSalary());
            row.put("deadline", f.getDeadline());
            row.put("applyUrl", f.getApplyUrl());
            row.put("createdAt", f.getCreatedAt());
            if (f.getDeadline() != null) {
                long daysLeft = ChronoUnit.DAYS.between(today, f.getDeadline());
                row.put("daysLeft", daysLeft);
                row.put("expired", daysLeft < 0);
                row.put("remind", daysLeft >= 0 && daysLeft <= 7);
            }
            return row;
        }).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", rows.size());
        result.put("items", rows);
        return Result.success(result);
    }

    /**
     * 已收藏岗位 ID 集合（用于前端高亮收藏状态）
     * GET /api/jobs/favorite/ids
     */
    @Operation(summary = "已收藏岗位 ID 集合")
    @GetMapping("/favorite/ids")
    public Result<Set<Long>> favoriteIds() {
        return Result.success(jobFavoriteService.listFavoriteJobIds(currentUserId()));
    }

    /**
     * 切换岗位收藏
     * POST /api/jobs/favorite/toggle  Body {"jobId": 1}
     */
    @Operation(summary = "切换（新增/取消）岗位收藏")
    @PostMapping("/favorite/toggle")
    public Result<Map<String, Object>> favoriteToggle(@RequestBody Map<String, Object> req) {
        if (req.get("jobId") == null) {
            return Result.error(400, "jobId 不能为空");
        }
        Long jobId;
        try {
            jobId = Long.valueOf(req.get("jobId").toString());
        } catch (NumberFormatException e) {
            return Result.error(400, "jobId 格式不正确");
        }
        JobPostingEntity job = jobAgentService.findById(jobId);
        if (job == null) {
            return Result.error(404, "岗位不存在或已下架");
        }
        boolean favorited = jobFavoriteService.toggle(currentUserId(), job);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("favorited", favorited);
        result.put("count", jobFavoriteService.countByUser(currentUserId()));
        return Result.success(result);
    }
}
