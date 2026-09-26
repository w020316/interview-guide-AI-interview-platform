package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.entity.JobPostingEntity;
import com.example.interview.service.AdminService;
import com.example.interview.service.job.JobAgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理后台接口（v1.31.4，仅 ROLE_ADMIN 可访问）
 * - 数据总览 + 手动刷新
 * - 岗位数据管理（下架/恢复/删除）
 * - 用户管理（禁用/解禁）
 * - 系统指标（AI 调用/缓存/SSE/JVM）
 */
@Tag(name = "管理后台", description = "仅管理员（ROLE_ADMIN）可访问；管理员名单由 APP_ADMIN_USERNAMES 配置")
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @Operation(summary = "数据总览")
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        return Result.success(adminService.overview());
    }

    @Operation(summary = "手动刷新岗位数据（管理员无限制）")
    @PostMapping("/jobs/refresh")
    public Result<JobAgentService.RefreshResult> refreshJobs() {
        return Result.success(adminService.refreshJobs());
    }

    @Operation(summary = "数据源健康视图（适配器状态 × 实际入库量）")
    @GetMapping("/sources")
    public Result<Map<String, Object>> sources() {
        return Result.success(adminService.sources());
    }

    @Operation(summary = "岗位列表（含失效，可按来源/招聘类型/状态/海内外范围检索）")
    @GetMapping("/jobs")
    public Result<Map<String, Object>> listJobs(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String recruitType,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Boolean overseas,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        // P3-03（2026-09-26）：非法招聘类型此前静默返回空列表
        String recruitTypeError = com.example.interview.service.job.RecruitType.validationError(recruitType);
        if (recruitTypeError != null) {
            return Result.error(400, recruitTypeError);
        }
        // 收口①（v1.44.0）：校验大小写不敏感放行（如 spring），但底层 cb.equal 查询大小写敏感，
        // 不归一会「判合法却查不到」。归一为规范 code；空串/null 为「不限」，原样透传。
        com.example.interview.service.job.RecruitType rt =
                com.example.interview.service.job.RecruitType.fromCode(recruitType);
        String normalizedRecruitType = (rt == null) ? recruitType : rt.name();
        // P3-04：size < 1 此前被静默钳成 1，返回条数与预期不符且无从察觉
        String sizeError = com.example.interview.util.PaginationSupport.validateSize(size);
        if (sizeError != null) {
            return Result.error(400, sizeError);
        }
Page<JobPostingEntity> result = adminService.listJobs(keyword, source, normalizedRecruitType, active, overseas, page, size);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", result.getTotalElements());
        body.put("page", result.getNumber());
        body.put("size", result.getSize());
        body.put("items", result.getContent());
        return Result.success(body);
    }

    @Operation(summary = "下架岗位")
    @PostMapping("/jobs/{id}/deactivate")
    public Result<Void> deactivateJob(@PathVariable Long id) {
        adminService.deactivateJob(id);
        return Result.success(null);
    }

    @Operation(summary = "恢复岗位")
    @PostMapping("/jobs/{id}/activate")
    public Result<Void> activateJob(@PathVariable Long id) {
        adminService.activateJob(id);
        return Result.success(null);
    }

    @Operation(summary = "删除岗位")
    @DeleteMapping("/jobs/{id}")
    public Result<Void> deleteJob(@PathVariable Long id) {
        adminService.deleteJob(id);
        return Result.success(null);
    }

    @Operation(summary = "用户列表（脱敏，含禁用状态）")
    @GetMapping("/users")
    public Result<Map<String, Object>> listUsers(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        // P3-04：size < 1 此前被静默钳成 1，返回条数与预期不符且无从察觉
        String sizeError = com.example.interview.util.PaginationSupport.validateSize(size);
        if (sizeError != null) {
            return Result.error(400, sizeError);
        }
Page<AdminService.UserView> result = adminService.listUsers(keyword, page, size);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", result.getTotalElements());
        body.put("page", result.getNumber());
        body.put("size", result.getSize());
        body.put("items", result.getContent());
        return Result.success(body);
    }

    @Operation(summary = "禁用用户")
    @PostMapping("/users/{id}/ban")
    public Result<Void> banUser(@PathVariable Long id) {
        // P2-07：传入当前管理员 ID，供服务端拦截「禁用自己 / 禁用最后一个管理员」
        adminService.banUser(id, currentUserId());
        return Result.success(null);
    }

    @Operation(summary = "解禁用户")
    @PostMapping("/users/{id}/unban")
    public Result<Void> unbanUser(@PathVariable Long id) {
        adminService.unbanUser(id);
        return Result.success(null);
    }

    @Operation(summary = "系统指标")
    @GetMapping("/metrics")
    public Result<Map<String, Object>> metrics() {
        return Result.success(adminService.metrics());
    }

    /** 当前登录用户 ID（JWT subject，由 JwtAuthFilter 写入 principal）；解析失败返回 null */
    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) return null;
        try {
            return Long.valueOf(auth.getPrincipal().toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}