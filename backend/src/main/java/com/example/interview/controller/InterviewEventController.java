package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.entity.InterviewEventEntity;
import com.example.interview.service.InterviewEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * 面试日历接口
 * - 查询/创建/更新/删除求职面试日程
 *
 * userId 一律从 JWT token 提取，防止 IDOR 越权。
 */
@Tag(name = "面试日历", description = "求职面试日程规划管理")
@RestController
@RequestMapping("/api/calendar/event")
public class InterviewEventController {

    @Autowired
    private InterviewEventService eventService;

    /** 从 SecurityContext 获取当前登录用户 ID（JWT subject） */
    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            throw new IllegalStateException("未认证用户");
        }
        return auth.getPrincipal().toString();
    }

    /**
     * 查询我的全部日程
     * GET /api/calendar/event/list
     */
    @Operation(summary = "日程列表")
    @GetMapping("/list")
    public Result<List<InterviewEventEntity>> list() {
        return Result.success(eventService.listByUser(currentUserId()));
    }

    /**
     * 创建日程
     * POST /api/calendar/event
     * Body: {"title":"字节跳动 · 后端一面","interviewAt":"2026-09-05T14:00:00","location":"视频面试","note":"...","interviewer":"HR 李工"}
     */
    @Operation(summary = "创建日程")
    @PostMapping
    public Result<InterviewEventEntity> create(@RequestBody Map<String, Object> req) {
        String userId = currentUserId();
        String title = (String) req.get("title");
        if (title == null || title.isBlank()) {
            return Result.error(400, "title（事件标题）不能为空");
        }
        LocalDateTime interviewAt = parseDateTime(req.get("interviewAt"));
        if (interviewAt == null) {
            return Result.error(400, "interviewAt（面试时间）格式错误，需要 ISO 格式，如 2026-09-05T14:00:00");
        }
        InterviewEventEntity event = InterviewEventEntity.builder()
                .title(title.trim())
                .interviewer((String) req.get("interviewer"))
                .location((String) req.get("location"))
                .note((String) req.get("note"))
                .interviewAt(interviewAt)
                .status(req.get("status") != null ? req.get("status").toString() : null)
                .build();
        return Result.success(eventService.create(userId, event));
    }

    /**
     * 更新日程（仅限本人）
     * PUT /api/calendar/event/{id}
     */
    @Operation(summary = "更新日程")
    @PutMapping("/{id}")
    public Result<InterviewEventEntity> update(@PathVariable Long id, @RequestBody Map<String, Object> req) {
        String userId = currentUserId();
        InterviewEventEntity updates = new InterviewEventEntity();
        if (req.get("title") != null) updates.setTitle(req.get("title").toString());
        if (req.get("interviewer") != null) updates.setInterviewer(req.get("interviewer").toString());
        if (req.get("location") != null) updates.setLocation(req.get("location").toString());
        if (req.get("note") != null) updates.setNote(req.get("note").toString());
        if (req.get("status") != null) updates.setStatus(req.get("status").toString());
        if (req.get("interviewAt") != null) {
            LocalDateTime at = parseDateTime(req.get("interviewAt"));
            if (at == null) return Result.error(400, "interviewAt 格式错误");
            updates.setInterviewAt(at);
        }
        return Result.success(eventService.update(id, userId, updates));
    }

    /**
     * 删除日程（仅限本人）
     * DELETE /api/calendar/event/{id}
     */
    @Operation(summary = "删除日程")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        eventService.delete(id, currentUserId());
        return Result.success();
    }

    /** 解析 ISO 时间字符串为 LocalDateTime（容错返回 null） */
    private LocalDateTime parseDateTime(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        try {
            return LocalDateTime.parse(value.toString());
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}