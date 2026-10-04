package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.service.TodoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 今日待办接口（第三批 B）。
 *
 * <p>只读聚合，零 AI。userId 一律从 JWT 提取，防 IDOR。
 */
@Tag(name = "今日待办", description = "登录后首页的待办聚合视图（read-time，零 AI）")
@RestController
@RequestMapping("/api/todo")
public class TodoController {

    private final TodoService todoService;

    public TodoController(TodoService todoService) {
        this.todoService = todoService;
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
     * 今日待办聚合
     * GET /api/todo/today?days=3
     *
     * <p>返回四组待办（待跟进投递 / 近期日程 / 临近截止 / 待投递）；
     * 某组数据源不可用时该组 {@code count=null} 且计入 {@code partialFailures}。
     */
    @Operation(summary = "今日待办聚合（read-time，零 AI）")
    @GetMapping("/today")
    public Result<Map<String, Object>> today(
            @RequestParam(required = false, defaultValue = "3") int days) {
        return Result.success(todoService.today(currentUserId(), days));
    }
}
