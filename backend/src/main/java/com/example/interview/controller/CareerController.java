package com.example.interview.controller;

import com.example.interview.common.RateLimitExceededException;
import com.example.interview.common.Result;
import com.example.interview.service.career.CareerProfileService;
import com.example.interview.service.career.SalaryCoachService;
import com.example.interview.util.PerUserRateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 求职 Skill 接口（v1.35.0）
 *
 * <p>落地抖音「VibeCoding大赏｜求职Skill」的方法论：
 * <ul>
 *   <li>{@code POST /api/career/mine} —— 职业资产四层挖掘（证据→行为→能力→可投岗位信号）</li>
 *   <li>{@code POST /api/career/plan} —— 岗位节奏计划（为什么适合/差距/30天case/赛道）</li>
 * </ul>
 *
 * userId 一律从 JWT 提取，不接受请求体传入（防 IDOR）。
 */
@Tag(name = "求职 Skill", description = "职业资产挖掘与求职节奏规划")
@RestController
@RequestMapping("/api/career")
public class CareerController {

    private static final Logger log = LoggerFactory.getLogger(CareerController.class);

    /**
     * 用户自述经历的请求体上限。
     * P2-12：直接引用服务层常量 —— 此前这里写 3000 却在下面按 {@code *2}（6000）校验，
     * 3000~6000 字的输入通过校验后被服务层静默截断到 3000，用户毫无感知。
     */
    private static final int MAX_NARRATIVE_LEN =
            com.example.interview.service.career.CareerProfileService.MAX_NARRATIVE_LEN;

    @Autowired
    private CareerProfileService careerProfileService;

    /** 谈薪助手（v1.85.0）：本地做金额换算，AI 只出策略与话术 */
    @Autowired
    private SalaryCoachService salaryCoachService;

    /**
     * 谈薪按用户限流（30 次/分钟）。
     *
     * <p>与 {@code AiRateLimitFilter} 的全局 AI 限流是两层不同目的：过滤器按路径做统一配额，
     * 这里防的是单用户短时间反复生成同一份建议（改一个数字就重调一次模型）。
     * 出口统一走 {@link RateLimitExceededException} → 真 429。
     */
    private final PerUserRateLimiter salaryLimiter = new PerUserRateLimiter(30, 60_000L);

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 从 SecurityContext 获取当前登录用户 ID（JWT subject） */
    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            throw new IllegalStateException("未认证用户");
        }
        return auth.getPrincipal().toString();
    }

    /**
     * 职业资产四层挖掘
     * POST /api/career/mine
     * Body: {"narrative":"我的经历……","targetTrack":"Java 后端(可选)"}
     *
     * 返回解析后的对象（positioning/assets/strengthTags/blindSpots/nextSteps），
     * 前端可直接渲染；模型输出非法 JSON 时由服务层兜底为空结构，接口仍返回 200。
     */
    @Operation(summary = "职业资产挖掘（证据→行为→能力→岗位信号）")
    @PostMapping("/mine")
    public Result<Map<String, Object>> mine(@RequestBody Map<String, Object> req) {
        String userId = currentUserId();
        String narrative = str(req.get("narrative"));
        if (narrative == null || narrative.isBlank()) {
            return Result.error(400, "请先描述你的真实经历（narrative 不能为空）");
        }
        if (narrative.length() > MAX_NARRATIVE_LEN) {
            return Result.error(400, "经历描述过长，请精简后再试（最多 " + MAX_NARRATIVE_LEN + " 字）");
        }
        String targetTrack = str(req.get("targetTrack"));

        String json = careerProfileService.mine(userId, narrative, targetTrack);
        return Result.success(parseToMap(json, "mine"));
    }

    /**
     * 岗位节奏计划
     * POST /api/career/plan
     * Body: {"targetJob":"Java 后端（可含 JD）","resumeText":"简历要点","mineSummary":"可选，挖掘摘要"}
     */
    @Operation(summary = "岗位节奏计划（why-fit / gap / 30天case / 赛道）")
    @PostMapping("/plan")
    public Result<Map<String, Object>> plan(@RequestBody Map<String, Object> req) {
        String userId = currentUserId();
        String targetJob = str(req.get("targetJob"));
        if (targetJob == null || targetJob.isBlank()) {
            return Result.error(400, "请提供目标岗位（targetJob 不能为空）");
        }
        String resumeText = strOrEmpty(req.get("resumeText"));
        String mineSummary = strOrEmpty(req.get("mineSummary"));

        String json = careerProfileService.plan(userId, targetJob, resumeText, mineSummary);
        return Result.success(parseToMap(json, "plan"));
    }

    /**
     * 谈薪助手（v1.85.0）
     * POST /api/career/salary
     * Body: {"mode":"EXPECTATION|OFFER|NEGOTIATION","targetJob":"Java 后端","city":"杭州",
     *        "years":3,"currentMonthly":18000,"expectedMonthly":25000,
     *        "offerMonthly":22000,"offerMonths":15,"competing":"…","situation":"…"}
     *
     * <p>薪资字段全部可选，但填了就按「元/月（税前）」理解并做边界校验；金额换算在服务端完成，
     * 缺失数据返回 null 而不是 0。AI 只负责策略与话术，不提供任何市场行情数字。
     */
    @Operation(summary = "谈薪助手（期望报价 / offer 评估 / 还价话术）")
    @PostMapping("/salary")
    public Result<Map<String, Object>> salary(@RequestBody Map<String, Object> req) {
        String userId = currentUserId();
        SalaryCoachService.SalaryRequest payload;
        try {
            payload = buildSalaryRequest(req);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
        if (!salaryLimiter.allow(userId)) {
            throw new RateLimitExceededException("谈薪建议生成太频繁了，请稍后再试");
        }
        return Result.success(salaryCoachService.coach(userId, payload));
    }

    /** 请求体 → 服务入参；非法数字/越界数值抛 IllegalArgumentException（→ 400） */
    private static SalaryCoachService.SalaryRequest buildSalaryRequest(Map<String, Object> req) {
        SalaryCoachService.Mode mode = SalaryCoachService.Mode.parse(str(req.get("mode")));
        String targetJob = str(req.get("targetJob"));
        if (targetJob == null || targetJob.isBlank()) {
            throw new IllegalArgumentException("请填写目标岗位（targetJob 不能为空）");
        }
        return new SalaryCoachService.SalaryRequest(
                mode,
                targetJob,
                strOrEmpty(req.get("city")),
                number(req.get("years"), "工作年限", 0, 60),
                number(req.get("currentMonthly"), "当前月薪", 1, 1_000_000),
                number(req.get("expectedMonthly"), "期望月薪", 1, 1_000_000),
                number(req.get("offerMonthly"), "offer 月薪", 1, 1_000_000),
                number(req.get("offerMonths"), "年薪月数", 1, 36),
                strOrEmpty(req.get("competing")),
                strOrEmpty(req.get("situation")));
    }

    /**
     * 可选数值字段解析：空值返回 null；非数字或越界抛 IllegalArgumentException。
     *
     * <p>不把「填错」静默当作「没填」——那会让用户以为建议已经考虑了他的薪资，
     * 实际模型拿到的却是「未填写」。
     */
    private static Integer number(Object value, String label, int min, int max) {
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        int parsed;
        try {
            parsed = (int) Math.round(Double.parseDouble(value.toString().trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + "请填写数字");
        }
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException(label + "需在 " + min + " ~ " + max + " 之间");
        }
        return parsed;
    }

    /** JSON 字符串 → Map；解析失败返回带 raw 的兜底结构（保证前端不白屏） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseToMap(String json, String context) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("求职 Skill 结果解析失败 context={} err={}", context, e.getMessage());
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("raw", json);
            return fallback;
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }

    /**
     * 可选文本参数归一：null 统一为 ""。
     *
     * <p>避免把 null 传入服务层参与提示词拼接（历史上出现过 "null" 字面量混入 prompt 的问题），
     * 同时让「未提供」与「提供空串」在服务层语义一致。
     */
    private static String strOrEmpty(Object v) {
        return v == null ? "" : v.toString();
    }
}
