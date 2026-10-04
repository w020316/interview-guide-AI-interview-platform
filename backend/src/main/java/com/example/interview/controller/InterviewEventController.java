package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.entity.InterviewEventEntity;
import com.example.interview.entity.JobApplicationEntity;
import com.example.interview.service.InterviewEventService;
import com.example.interview.service.job.JobApplicationService;
import com.example.interview.util.RequestFieldUtil;
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
import java.util.Set;

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

    @Autowired
    private JobApplicationService applicationService;

    /**
     * 日历状态合法取值（第六轮 P1-04，v1.47.0）。
     *
     * <p>取值来源（grep 前端确认，非猜测）：{@code CalendarView.vue} 的 {@code <option>}
     * 与 {@code utils/calendar.ts} 的 {@code EventStatus} 均为 UPCOMING / DONE / CANCELLED；
     * 实体默认值为 UPCOMING。
     *
     * <p>此前 create/update 只判 null 就 {@code toString()} 入库，任意串（如
     * {@code NOT_A_STATUS_XYZ} / {@code 999} / 空串）都会成功落库——前端按 status 分支渲染，
     * 非法值会落到「待面试」的兜底分支，用户设的「已取消」可能被错误展示。
     */
    private static final Set<String> ALLOWED_STATUS = Set.of("UPCOMING", "DONE", "CANCELLED");

    /**
     * 校验日历 status 取值。
     *
     * @return 合法（含 null=未提供）返回 {@code null}；非法返回面向用户的中文提示
     */
    private static String statusValidationError(String status) {
        // null = 未提供 → 沿用实体默认/不修改；非空（含空串）必须在白名单内，否则拒绝。
        if (status == null) {
            return null;
        }
        if (ALLOWED_STATUS.contains(status)) {
            return null;
        }
        // 不做静默钳制：把用户本意 CANCELLED 改成「待面试」属于篡改用户意图。
        return "status 取值非法，仅支持：UPCOMING（待面试）、DONE（已完成）、CANCELLED（已取消）";
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
        // 第六轮 P1-03：不再无条件强转（数字/数组/对象 → 400 而非 500）
        RequestFieldUtil.TextField titleF = RequestFieldUtil.text(req, "title");
        if (titleF.hasTypeError()) {
            return Result.error(400, RequestFieldUtil.typeError("title"));
        }
        String title = titleF.value();
        if (title == null || title.isBlank()) {
            return Result.error(400, "title（事件标题）不能为空");
        }
        LocalDateTime interviewAt = parseDateTime(req.get("interviewAt"));
        if (interviewAt == null) {
            return Result.error(400, "interviewAt（面试时间）格式错误，需要 ISO 格式，如 2026-09-05T14:00:00");
        }
        RequestFieldUtil.TextField ivF = RequestFieldUtil.text(req, "interviewer");
        if (ivF.hasTypeError()) {
            return Result.error(400, RequestFieldUtil.typeError("interviewer"));
        }
        RequestFieldUtil.TextField locF = RequestFieldUtil.text(req, "location");
        if (locF.hasTypeError()) {
            return Result.error(400, RequestFieldUtil.typeError("location"));
        }
        RequestFieldUtil.TextField noteF = RequestFieldUtil.text(req, "note");
        if (noteF.hasTypeError()) {
            return Result.error(400, RequestFieldUtil.typeError("note"));
        }
        // 第六轮 P1-04：status 必须命中枚举白名单，非法值返回 400（不静默钳制、不原样落库）
        RequestFieldUtil.TextField statusF = RequestFieldUtil.text(req, "status");
        if (statusF.hasTypeError()) {
            return Result.error(400, RequestFieldUtil.typeError("status"));
        }
        String statusError = statusValidationError(statusF.value());
        if (statusError != null) {
            return Result.error(400, statusError);
        }
        // v1.61.0：可选关联投递记录。必须做归属校验（防 IDOR——
        // 否则可把日程挂到他人投递记录上，时序视图会泄漏他人岗位/公司信息）。
        Long applicationId = null;
        if (req.get("applicationId") != null) {
            RequestFieldUtil.NumberField appIdF = RequestFieldUtil.number(req, "applicationId");
            if (appIdF.hasTypeError()) {
                return Result.error(400, RequestFieldUtil.numberTypeError("applicationId"));
            }
            applicationId = appIdF.value() == null ? null : appIdF.value().longValue();
            if (applicationId != null && applicationService.findOwned(userId, applicationId) == null) {
                return Result.error(404, "未找到该投递记录，无法关联");
            }
        }
        InterviewEventEntity event = InterviewEventEntity.builder()
                .title(title.trim())
                .interviewer(ivF.value())
                .location(locF.value())
                .note(noteF.value())
                .interviewAt(interviewAt)
                .status(statusF.value())
                .applicationId(applicationId)
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
        // 实体字段默认值会把 status 预置为 "UPCOMING"，导致 service.update 的
        // `if (updates.getStatus() != null)` 恒真 —— 每次更新都会把已取消/已完成的日程
        // 悄悄重置为「待面试」（篡改用户意图）。这里显式清空，让「未提供 status」真正表达为 null。
        updates.setStatus(null);
        if (req.get("title") != null) updates.setTitle(req.get("title").toString());
        if (req.get("interviewer") != null) updates.setInterviewer(req.get("interviewer").toString());
        if (req.get("location") != null) updates.setLocation(req.get("location").toString());
        if (req.get("note") != null) updates.setNote(req.get("note").toString());
        // 第六轮 P1-04：update 同样必须校验 status 白名单，非法值 400（与 create 一致）
        if (req.get("status") != null) {
            RequestFieldUtil.TextField statusF = RequestFieldUtil.text(req, "status");
            if (statusF.hasTypeError()) {
                return Result.error(400, RequestFieldUtil.typeError("status"));
            }
            String statusError = statusValidationError(statusF.value());
            if (statusError != null) {
                return Result.error(400, statusError);
            }
            updates.setStatus(statusF.value());
        }
        if (req.get("interviewAt") != null) {
            LocalDateTime at = parseDateTime(req.get("interviewAt"));
            if (at == null) return Result.error(400, "interviewAt 格式错误");
            updates.setInterviewAt(at);
        }
        // v1.61.0：关联字段的三态处理（区别于上面几行的二态「有则改」）：
        //   ① 请求体**未带** applicationId          → 保持原关联不动
        //   ② 带了且为 null / 空串 / 0              → 取消关联（写回 null）
        //   ③ 带了正数                              → 关联到该记录（须归属校验，防 IDOR）
        // 用独立标志位表达「要清除」，因为 null 在本方法里同时意味着「未提供」，
        // 只靠实体字段无法区分这两者。
        boolean clearLink = false;
        if (req.containsKey("applicationId")) {
            Object raw = req.get("applicationId");
            boolean blank = raw == null
                    || (raw instanceof String s && s.trim().isEmpty())
                    || "0".equals(String.valueOf(raw).trim());
            if (blank) {
                clearLink = true;
            } else {
                RequestFieldUtil.NumberField appIdF = RequestFieldUtil.number(req, "applicationId");
                if (appIdF.hasTypeError() || appIdF.value() == null) {
                    return Result.error(400, RequestFieldUtil.numberTypeError("applicationId"));
                }
                Long appId = appIdF.value().longValue();
                if (applicationService.findOwned(userId, appId) == null) {
                    return Result.error(404, "未找到该投递记录，无法关联");
                }
                updates.setApplicationId(appId);
            }
        }
        InterviewEventEntity updated = clearLink
                ? eventService.updateAndClearApplicationLink(id, userId, updates)
                : eventService.update(id, userId, updates);
        return Result.success(updated);
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