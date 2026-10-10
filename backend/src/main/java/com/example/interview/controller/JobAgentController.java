package com.example.interview.controller;

import com.example.interview.common.ResourceNotFoundException;
import com.example.interview.common.Result;
import com.example.interview.entity.JobFavoriteEntity;
import com.example.interview.entity.JobPostingEntity;
import com.example.interview.service.JobFavoriteService;
import com.example.interview.service.job.JobAgentService;
import com.example.interview.service.job.JobMatchService;
import com.example.interview.util.RequestFieldUtil;
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
import java.util.regex.Pattern;

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

    /** 简历匹配请求的文本长度上限（B-15），与 CareerController 同口径 */
    private static final int MAX_RESUME_TEXT_LEN = 20000;

    /** 手动刷新按用户限流：防止反复触发昂贵的第三方抓取（v1.31.4 B-10） */
    private final com.example.interview.util.PerUserRateLimiter refreshLimiter =
            new com.example.interview.util.PerUserRateLimiter(1, 5 * 60 * 1000L);

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

    /** 安全读取 Long（非法/缺省返回 null） */
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

    /** 搜索关键词允许的字符：中文、英文、数字、空格与常见符号（P2-04） */
    private static final Pattern KEYWORD_ALLOWED =
            Pattern.compile("^[\\u4e00-\\u9fa5a-zA-Z0-9 +#._\\-/&·]*$");
    private static final int KEYWORD_MAX_LENGTH = 50;

    /**
     * 搜索关键词白名单校验（P2-04），返回 null 表示通过。
     *
     * 背景：云端边缘安全网关（Render 边缘 WAF）对含 SQL 注入特征的查询串直接返回
     * 403 + HTML 拦截页，且该响应**不带 CORS 头** → 浏览器只能上报 net::ERR_FAILED，
     * 前端既拿不到 403 也拿不到响应体，曾据此误报「后端冷启动」并静默重放一次
     * （见 UX 测试报告 P2-04）。
     *
     * 与其让请求被应用之外的网关拦下、给出误导性提示，不如在入口处显式拒绝并说明原因。
     * 白名单覆盖 "C++" / "C#" / "node.js" / "Java/Python" / "前端 开发" 等真实搜索；
     * 引号、分号、注释符、尖括号等注入特征则在到达网关之前就被挡下。
     */
    private String validateKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) return null;
        String trimmed = keyword.trim();
        if (trimmed.length() > KEYWORD_MAX_LENGTH) {
            return "搜索关键词过长（最多 " + KEYWORD_MAX_LENGTH + " 个字符），请缩短后重试";
        }
        if (!KEYWORD_ALLOWED.matcher(trimmed).matches()) {
            return "搜索关键词包含不支持的字符，请仅使用中英文、数字与常见符号（+ # . - _ / &）";
        }
        return null;
    }

    /**
     * 岗位列表（多条件筛选）
     * GET /api/jobs?keyword=java&industry=互联网&jobType=技术&location=深圳&recruitType=AUTUMN&source=内置精选&degree=本科及以上&experience=1-3 年&page=0&size=10
     */
    @Operation(summary = "岗位列表（多条件筛选搜索，含海外/远程分栏）")
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
            // v1.38.0：海外/远程分栏——true 仅海外源、false 仅国内源、缺省不限
            @RequestParam(required = false) Boolean overseas,
            // N2：默认隐藏已截止——true 排除已截止岗位（保留「长期有效」与在招）；
            // 前端默认传 true，此处默认 false 以保持既有调用方行为不变
            @RequestParam(required = false, defaultValue = "false") boolean hideExpired,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "10") int size) {
        // P2-04：先做关键词白名单校验，避免请求被边缘 WAF 拦下后前端拿不到任何可解释信息
        String keywordError = validateKeyword(keyword);
        if (keywordError != null) {
            return Result.error(400, keywordError);
        }
        // P3-04（2026-09-26）：size < 1 此前被静默钳成 1，返回条数与预期不符且无从察觉
        String sizeError = com.example.interview.util.PaginationSupport.validateSize(size);
        if (sizeError != null) {
            return Result.error(400, sizeError);
        }
        // P3-03（2026-09-26）：非法招聘类型此前静默返回 total=0，用户会以为「真的没有岗位」。
        // 取值清单见 RecruitType（后端单一来源）；空值=「不限」，放行。
        String recruitTypeError = com.example.interview.service.job.RecruitType.validationError(recruitType);
        if (recruitTypeError != null) {
            return Result.error(400, recruitTypeError);
        }
        // 收口①（v1.44.0）：校验是大小写不敏感放行（如 spring），但底层查询用 cb.equal 是
        // 大小写敏感的——若不归一，spring 会被判「合法」却查不到（total=0），正是 P3-03 要
        // 消灭的「静默空结果」。在此把合法取值归一为规范 code；空串/null 为「不限」，fromCode
        // 返回 null → 原样透传，语义与既有行为完全一致。
        com.example.interview.service.job.RecruitType rt =
                com.example.interview.service.job.RecruitType.fromCode(recruitType);
        String normalizedRecruitType = (rt == null) ? recruitType : rt.name();
        Page<JobPostingEntity> result = jobAgentService.search(
                keyword, industry, jobType, location, normalizedRecruitType, source, degree, experience,
                overseas, hideExpired, page, size);
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
            // v1.48.0（第六轮 P1-05）：统一「资源不存在」口径为 HTTP 404 + code 404。
            // 此前这里 return Result.error(404,...) 走「HTTP 200 + code 404」通道，
            // 与 GET /api/session/{id}（抛 ResourceNotFoundException → HTTP 404）不一致；
            // 契约（docs/api-error-contract.md §3）明确 ResourceNotFoundException → 404。
            throw new ResourceNotFoundException("岗位不存在或已下架");
        }
        return Result.success(job);
    }

    /**
     * 筛选面板元数据：行业/职位类型/来源/各招聘类型数量/最近更新时间
     * GET /api/jobs/meta
     */
    @Operation(summary = "筛选面板元数据")
    @GetMapping("/meta")
    public Result<Map<String, Object>> meta(
            // P2-08：分栏口径——「全部国内」传 false、「海外远程」传 true，缺省为全局
            @RequestParam(required = false) Boolean overseas) {
        return Result.success(jobAgentService.meta(overseas));
    }

    @Operation(summary = "简历匹配推荐（岗位吻合度打分）")
    @PostMapping("/match")
    public Result<Map<String, Object>> resumeMatch(@RequestBody Map<String, Object> req) {
        String resumeText = req.get("resumeText") != null ? req.get("resumeText").toString() : "";
        if (resumeText.isBlank()) {
            return Result.error(400, "请提供简历内容");
        }
        // B-15：简历文本长度上限，防止超大 body 放大匹配开销（与 CareerController 同口径）
        if (resumeText.length() > MAX_RESUME_TEXT_LEN) {
            return Result.error(400, "简历内容过长（上限 " + MAX_RESUME_TEXT_LEN + " 字），请精简后重试");
        }
        // B-04：limit 此前用 Integer.parseInt 直接解析——非数字抛 NumberFormatException → 500，
        // 且无上下界（传 100000 会放大匹配开销）。改用 RequestFieldUtil 校验并钳制到 [1,50]。
        var limitField = RequestFieldUtil.number(req, "limit");
        if (limitField.hasTypeError()) {
            return Result.error(400, RequestFieldUtil.numberTypeError("limit"));
        }
        int limit = limitField.value() == null ? 10 : Math.max(1, Math.min(50, limitField.value().intValue()));

        // R10-07：技能画像只提取一次。extractSkills 内部要遍历整个 SKILL_MATCHERS 词表，
        // 此前调了两次（一次进预筛、一次填 topSkills），纯属重复计算。
        var skills = jobMatchService.extractSkills(resumeText);
        var matched = jobMatchService.match(resumeText, jobAgentService.activeJobsMatchingSkills(skills), limit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", matched.size());
        result.put("topSkills", skills);
        List<Map<String, Object>> items = matched.stream().map(m -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("job", m.job());
            row.put("matchScore", m.matchScore());
            row.put("matchedSkills", m.matchedSkills());
            // 竞品清单 #20：岗位详情显式「短板」——JD 要求但简历没有的技能（后端规则推导，零 AI）
            row.put("missingSkills", m.missingSkills());
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
        // 按用户限流：同一用户 5 分钟内仅允许手动刷新一次，防刷第三方抓取配额（B-10）；管理员无限制（v1.31.4）
        if (!com.example.interview.security.RoleUtil.isCurrentUserAdmin()
                && !refreshLimiter.allow(currentUserId())) {
            return Result.error(429, "刷新过于频繁，请 5 分钟后再试");
        }
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
        List<Map<String, Object>> rows = items.stream()
                .map(f -> buildFavoriteRow(f, today))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", rows.size());
        result.put("items", rows);
        return Result.success(result);
    }

    /** 组装单条收藏行（收藏列表与偏好设置端点复用，保证字段一致）。 */
    private Map<String, Object> buildFavoriteRow(JobFavoriteEntity f, LocalDate today) {
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
        // 第三批 H：偏好档位（无则 null=未标记，不得默认成任何一档）
        row.put("preference", f.getPreference());
        row.put("createdAt", f.getCreatedAt());
        if (f.getDeadline() != null) {
            long daysLeft = ChronoUnit.DAYS.between(today, f.getDeadline());
            row.put("daysLeft", daysLeft);
            row.put("expired", daysLeft < 0);
            row.put("remind", daysLeft >= 0 && daysLeft <= 7);
        }
        return row;
    }

    /**
     * 设置/清除岗位收藏偏好档位（第三批 H）
     * POST /api/jobs/favorite/preference  Body: {"jobId":1,"preference":"STRONG"}
     *
     * <p>{@code preference:null} 清除标记；同值幂等。合法取值见
     * {@link com.example.interview.entity.JobFavoriteEntity} 的 PREFERENCE_* 常量。
     */
    @Operation(summary = "设置岗位收藏偏好档位（四档）")
    @PostMapping("/favorite/preference")
    public Result<Map<String, Object>> setFavoritePreference(@RequestBody Map<String, Object> req) {
        Long jobId = longVal(req.get("jobId"));
        if (jobId == null) {
            return Result.error(400, "jobId 不能为空");
        }
        String preference = req.get("preference") == null ? null : req.get("preference").toString();
        // service 内部对非法档位抛 IllegalArgumentException（→400），未收藏返回 null（→404）
        JobFavoriteEntity updated = jobFavoriteService.setPreference(currentUserId(), jobId, preference);
        if (updated == null) {
            throw new ResourceNotFoundException("未找到该收藏记录");
        }
        return Result.success(buildFavoriteRow(updated, LocalDate.now()));
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
            // v1.48.0（第六轮 P1-05）：统一为 HTTP 404 + code 404（见 detail 方法注释）
            throw new ResourceNotFoundException("岗位不存在或已下架");
        }
        boolean favorited = jobFavoriteService.toggle(currentUserId(), job);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("favorited", favorited);
        result.put("count", jobFavoriteService.countByUser(currentUserId()));
        return Result.success(result);
    }
}
