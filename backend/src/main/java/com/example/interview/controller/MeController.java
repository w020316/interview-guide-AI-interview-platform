package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.service.BackupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 个人数据导出 / 导入接口（第三批 C）。
 *
 * <p>userId 一律从 JWT 提取，防 IDOR。
 * <ul>
 *   <li>{@code GET /api/me/export} —— 导出为单 JSON（不含 password/salt/token）；</li>
 *   <li>{@code POST /api/me/import} —— dryRun 预览 / 原子提交；指纹冲突 → 409；importId 幂等。</li>
 * </ul>
 */
@Tag(name = "个人数据", description = "个人数据导出与导入（备份/迁移）")
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final BackupService backupService;

    public MeController(BackupService backupService) {
        this.backupService = backupService;
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
     * 导出个人数据
     * GET /api/me/export?includeConversations=false
     */
    @Operation(summary = "导出个人数据（单 JSON）")
    @GetMapping("/export")
    public Result<Map<String, Object>> export(
            @RequestParam(required = false, defaultValue = "false") boolean includeConversations) {
        return Result.success(backupService.export(currentUserId(), includeConversations));
    }

    /**
     * 导入个人数据
     * POST /api/me/import
     * Body: {mode:'merge'|'replace', dryRun, force, expectedFingerprint, importId, payload}
     *
     * <p>dryRun 零写库；replace + 指纹不符 + 未 force → HTTP 409（ConflictException）；
     * 同 importId 重复提交返回上次结果（不二次导入）。
     */
    @Operation(summary = "导入个人数据（dryRun 预览 + 原子写入）")
    @PostMapping("/import")
    public Result<Map<String, Object>> importData(@RequestBody Map<String, Object> req) {
        return Result.success(backupService.importData(currentUserId(), req));
    }
}
