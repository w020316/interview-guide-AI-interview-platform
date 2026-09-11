package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.entity.JobPostingEntity;
import com.example.interview.service.AdminService;
import com.example.interview.service.job.JobAgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
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

    @Operation(summary = "岗位列表（含失效，可检索）")
    @GetMapping("/jobs")
    public Result<Map<String, Object>> listJobs(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<JobPostingEntity> result = adminService.listJobs(keyword, page, size);
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
        adminService.banUser(id);
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
}