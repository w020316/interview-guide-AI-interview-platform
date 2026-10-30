package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.entity.AiTaskEntity;
import com.example.interview.service.task.AiTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 任务中心接口（阶段六 M2）。
 *
 * <p>契约见 {@code docs/quality-loop-2026-10-30/06-调研与立项.md} §6.2：
 * 创建 / 查询 / 列表 / 取消 / 汇总。所有接口的 {@code userId} 一律从 SecurityContext 取，
 * **不接受请求体传入**（防 IDOR）。
 *
 * <p>开关：{@code app.task-center.enabled} 默认 false。关闭时接口返回 503「任务中心未启用」，
 * 保证回滚时不是「静默返回空列表」而是明确告知——前者会让前端以为「你没有任务」。
 */
@Tag(name = "AI 任务中心", description = "长耗时 AI 调用的异步任务查询与取消")
@RestController
@RequestMapping("/api/tasks")
public class AiTaskController {

    /** 单次返回上限：任务列表是辅助信息，不允许一次拉全量 */
    private static final int MAX_PAGE_SIZE = 50;
    /** payload 快照长度上限（避免把整份简历塞进任务表） */
    private static final int MAX_PAYLOAD_LEN = 2000;

    private final AiTaskService taskService;

    @Value("${app.task-center.enabled:false}")
    private boolean enabled;

    public AiTaskController(AiTaskService taskService) {
        this.taskService = taskService;
    }

    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            throw new IllegalStateException("未认证用户");
        }
        return auth.getPrincipal().toString();
    }

    private static final String DISABLED_MSG = "任务中心未启用";

    /**
     * 创建任务。
     * Body: {"type":"interview.questions","idempotencyKey":"...","payload":"...","maxAttempt":2}
     *
     * <p>幂等语义：同用户同键在「未失败」状态下返回原任务（HTTP 200 + 原任务），
     * 前端无需处理 409，也不会因连点产生重复计费。
     */
    @Operation(summary = "创建 AI 异步任务（幂等）")
    @PostMapping
    public Result<AiTaskEntity> create(@RequestBody Map<String, Object> req) {
        if (!enabled) {
            return Result.error(503, DISABLED_MSG);
        }
        String userId = currentUserId();
        String type = str(req.get("type"));
        String key = str(req.get("idempotencyKey"));
        String payload = str(req.get("payload"));
        if (type == null || type.isBlank()) {
            return Result.error(400, "缺少任务类型（type）");
        }
        if (key == null || key.isBlank()) {
            return Result.error(400, "缺少幂等键（idempotencyKey）");
        }
        if (payload != null && payload.length() > MAX_PAYLOAD_LEN) {
            return Result.error(400, "任务参数过长（最多 " + MAX_PAYLOAD_LEN + " 字符）");
        }
        int maxAttempt = 1;
        Object rawMax = req.get("maxAttempt");
        if (rawMax instanceof Number n) {
            maxAttempt = n.intValue();
        }
        return Result.success(taskService.create(userId, type.trim(), key.trim(), payload, maxAttempt));
    }

    @Operation(summary = "查询单个任务（仅本人）")
    @GetMapping("/{id}")
    public Result<AiTaskEntity> get(@PathVariable Long id) {
        if (!enabled) {
            return Result.error(503, DISABLED_MSG);
        }
        return Result.success(taskService.findOwned(currentUserId(), id));
    }

    @Operation(summary = "我的任务列表（分页）")
    @GetMapping
    public Result<Map<String, Object>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (!enabled) {
            return Result.error(503, DISABLED_MSG);
        }
        Page<AiTaskEntity> result = taskService.listOwned(currentUserId(), status, page,
                Math.min(size, MAX_PAGE_SIZE));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", result.getContent());
        data.put("total", result.getTotalElements());
        data.put("page", result.getNumber());
        data.put("size", result.getSize());
        return Result.success(data);
    }

    @Operation(summary = "取消任务（仅本人、仅未结束）")
    @PostMapping("/{id}/cancel")
    public Result<AiTaskEntity> cancel(@PathVariable Long id) {
        if (!enabled) {
            return Result.error(503, DISABLED_MSG);
        }
        return Result.success(taskService.cancel(currentUserId(), id));
    }

    @Operation(summary = "任务汇总（排队/运行/失败数）")
    @GetMapping("/summary")
    public Result<Map<String, Long>> summary() {
        if (!enabled) {
            return Result.error(503, DISABLED_MSG);
        }
        return Result.success(taskService.summary(currentUserId()));
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
