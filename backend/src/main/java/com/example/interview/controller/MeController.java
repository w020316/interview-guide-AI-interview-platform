package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.service.BackupService;
import com.example.interview.service.UserAiKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
    private final UserAiKeyService userAiKeyService;

    public MeController(BackupService backupService, UserAiKeyService userAiKeyService) {
        this.backupService = backupService;
        this.userAiKeyService = userAiKeyService;
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

    // ── 自持 AI Key（第四批 · 竞品清单 #15）────────────────────────────────
    // 安全边界：Key 以 AES-256-GCM 密文落库（主密钥由 JWT_SECRET 派生）；
    // 对外接口只回显掩码，永不回显完整 Key；baseUrl 强制 https 且拒绝私网地址（防 SSRF）。
    // 清除后回到平台 Key 兜底，行为与 1.51.x 完全一致（功能默认关闭）。

    @Operation(summary = "查询自持 AI Key 设置（Key 只回显掩码）")
    @GetMapping("/ai-key")
    public Result<Map<String, Object>> getAiKey() {
        return Result.success(userAiKeyService.viewOf(currentUserId())
                .map(v -> Map.<String, Object>of("configured", true, "keyMasked", v.keyMasked(),
                        "baseUrl", v.baseUrl(), "model", v.model(), "updatedAt", String.valueOf(v.updatedAt())))
                .orElseGet(() -> Map.of("configured", false)));
    }

    @Operation(summary = "保存自持 AI Key（OpenAI 兼容端点，必须 https）")
    @PutMapping("/ai-key")
    public Result<Map<String, Object>> saveAiKey(@RequestBody Map<String, Object> req) {
        String apiKey = str(req.get("apiKey"));
        String baseUrl = str(req.get("baseUrl"));
        String model = str(req.get("model"));
        userAiKeyService.save(currentUserId(), apiKey, baseUrl, model);
        var v = userAiKeyService.viewOf(currentUserId()).orElseThrow();
        return Result.success(Map.of("configured", true, "keyMasked", v.keyMasked(),
                "baseUrl", v.baseUrl(), "model", v.model()));
    }

    @Operation(summary = "清除自持 AI Key（回到平台 Key 兜底）")
    @DeleteMapping("/ai-key")
    public Result<Map<String, Object>> deleteAiKey() {
        userAiKeyService.delete(currentUserId());
        return Result.success(Map.of("configured", false));
    }

    @Operation(summary = "测试自持 AI Key 连通性（消耗你自己的额度，不消耗平台额度）")
    @PostMapping("/ai-key/test")
    public Result<Map<String, Object>> testAiKey() {
        return Result.success(userAiKeyService.testCall(currentUserId()));
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
