package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.service.JobAnalysisService;
import com.example.interview.service.job.AtsJobResolver;
import com.example.interview.service.job.JobPageFetcher;
import com.example.interview.util.JsonRepairUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 岗位分析接口（参考 Easy-Job-Tutor 项目）
 * - POST /api/job/analyze  JD 岗位分析
 * - POST /api/job/gap      差距诊断（简历 vs JD）
 * - POST /api/job/letter   求职信/申请邮件/内推私信生成
 */
@Tag(name = "岗位分析", description = "JD分析、差距诊断、求职信生成")
@RestController
@RequestMapping("/api/job")
public class JobAnalysisController {

    /** 仅用于判断「AI 抽取结果是否三字段全空」，与业务 JSON 序列化无关 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private JobAnalysisService jobAnalysisService;

    @Autowired
    private JobPageFetcher jobPageFetcher;

    @Autowired
    private AtsJobResolver atsJobResolver;

    /** 从 SecurityContext 获取当前登录用户 ID */
    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            throw new IllegalStateException("未认证用户");
        }
        return auth.getPrincipal().toString();
    }

    /**
     * 从岗位链接导入 JD（竞品 #14）
     * POST /api/job/import-url
     * Body: {"url": "https://..."}
     *
     * <p>抓取用户粘贴的招聘页面 → 提纯正文 → AI 提炼岗位名称/公司/JD 正文。
     * <b>只提取、不编造</b>：页面上没有的字段返回空字符串。
     *
     * <p>返回值：{@code {jobTitle, company, jobDescription, sourceUrl}}
     * —— 前端把 {@code jobDescription} 回填进 JD 输入框，用户可编辑后再走既有的
     * {@code /api/job/analyze}。**不在这里直接串联分析**：那会让用户失去「核对/修正」的机会，
     * 而抓取结果本就可能有噪声。
     */
    @Operation(summary = "从岗位链接导入 JD：抓取页面并提取岗位名称/公司/职位描述")
    @PostMapping("/import-url")
    public Result<Map<String, Object>> importFromUrl(@RequestBody Map<String, String> request) {
        String url = request.get("url");
        if (url == null || url.trim().isEmpty()) {
            return Result.error(400, "链接不能为空");
        }
        currentUserId();

        // ── 路径 1（主力）：ATS 公开 API ──
        // Ashby / Greenhouse 的托管岗位页虽是 SPA（HTML 抓不到），但它们提供**公开、
        // 免鉴权、含完整 JD 正文**的 JSON API。2026-10-04 实测：Ashby 返回 descriptionPlain
        // 4482 字、Greenhouse 返回 content 5654 字，均为雇主发布的原始 JD。
        // 这是**最可靠也最诚实**的路径——拿到的就是原文，无需模型从噪声里猜。
        // 注意：url 未做 SSRF 校验，但本路径只会去 api.ashbyhq.com / boards-api.greenhouse.io
        // 这两个**写死的**受信主机，用户输入只参与拼 slug/id（且已被正则严格限定字符集），
        // 因此不存在 SSRF 面；真正需要校验的是下面的自由抓取路径。
        Optional<AtsJobResolver.ResolvedJob> ats = atsJobResolver.resolve(url.trim());
        if (ats.isPresent()) {
            AtsJobResolver.ResolvedJob job = ats.get();
            Map<String, Object> payload = new LinkedHashMap<>();
            // 字段直接取自雇主公开数据，不经模型 —— 前端按同一份契约解析
            Map<String, Object> extracted = new LinkedHashMap<>();
            extracted.put("jobTitle", job.title());
            extracted.put("company", job.company());
            extracted.put("jobDescription", job.descriptionPlain());
            payload.put("extracted", extracted);
            payload.put("sourceUrl", job.sourceUrl());
            payload.put("pageText", job.descriptionPlain());
            payload.put("source", job.atsName());     // 让前端能说清「这段 JD 来自 Ashby 公开接口」
            payload.put("location", job.location());
            return Result.success(payload);
        }

        // ── 路径 2（退化）：服务端 HTML 抓取 ──
        // 抓取（内部已完成 SSRF 校验；三态结果必须分别处理，不能都归成一种错误）
        JobPageFetcher.FetchedPage page = jobPageFetcher.fetch(url);
        // reason 一并透出：前端据此区分「需要登录 / 动态渲染 / 反爬拦截」，给出不同下一步提示
        Map<String, Object> reasonPayload = Map.of("reason", page.reason().name());
        if (page.outcome() == JobPageFetcher.Outcome.FAILED) {
            // SSRF 被拒时 finalUrl 为 null；此时原始输入本身就是问题所在，不能回显未校验的 URL
            return Result.error(400, page.message(), reasonPayload);
        }
        if (page.outcome() == JobPageFetcher.Outcome.SPA_OR_EMPTY) {
            // 与「抓取失败」区分开：技术上成功了，只是这个页面需要浏览器渲染。
            // 用 422 语义（unprocessable）而非 400，但按项目惯例 HTTP 仍返回 200。
            return Result.error(422, page.message(), reasonPayload);
        }

        // 提取：AI 只做抽取；失败时 callAi 会返回带 error 字段的 JSON，这里如实透出
        String json = jobAnalysisService.extractJobFromPage(page.text());
        if (json == null || json.contains("\"error\"")) {
            return Result.error(500, "已抓取到网页内容，但解析岗位信息失败，请重试或改为手动粘贴");
        }

        // ── 「抓到了正文，但一个字段都没抽出来」不能当成功下发（v1.62.1）──
        // 提示词明确要求「网页里没有明确写的字段一律填空字符串」，所以三字段全空是
        // 模型**诚实的回答**：它认为这个页面里根本没有岗位信息。而「正文够长但不是岗位页」
        // 是真实存在的（线上实测：维基百科词条能通过 looksLikeJobPage 的特征校验）。
        // 此前这里直接 Result.success，前端会拿到一段空 JD 并渲染出一屏「—」——
        // 用户以为导入成功了，实际什么都没有，也没有任何错误提示。
        // 这正是项目红线所禁的「解析失败但 HTTP 200」的脏数据，故如实分型为「不是岗位页」。
        if (isBlankExtraction(json)) {
            Map<String, Object> notJobPage = Map.of("reason", JobPageFetcher.Reason.NOT_A_JOB_PAGE.name());
            return Result.error(422,
                    "这个页面看起来不是岗位详情页，没能读到岗位信息。请确认链接指向具体岗位，或手动粘贴 JD 文本",
                    notJobPage);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("extracted", json);
        payload.put("sourceUrl", page.finalUrl());
        // 把原始正文也带上：AI 抽漏时用户仍能拿到可用文本，不必重新抓一遍
        payload.put("pageText", page.text());
        payload.put("source", "web");
        return Result.success(payload);
    }

    /**
     * JD 岗位分析
     * POST /api/job/analyze
     * Body: {"jobDescription": "..."}
     */
    @Operation(summary = "JD 岗位分析：拆解职责、硬技能、软技能、隐性条件、关键词")
    @PostMapping("/analyze")
    public Result<String> analyze(@RequestBody Map<String, String> request) {
        String jobDescription = request.get("jobDescription");
        if (jobDescription == null || jobDescription.trim().isEmpty()) {
            return Result.error(400, "岗位描述不能为空");
        }
        // currentUserId() 用于触发认证校验，确保已登录
        currentUserId();
        String result = jobAnalysisService.analyzeJobDescription(jobDescription);
        return Result.success(result);
    }

    /**
     * 差距诊断：简历 vs JD 逐条对比
     * POST /api/job/gap
     * Body: {"resumeText": "...", "jobDescription": "..."}
     */
    @Operation(summary = "差距诊断：简历 vs JD 逐条对比（强证据/弱证据/缺口）")
    @PostMapping("/gap")
    public Result<String> gap(@RequestBody Map<String, String> request) {
        String resumeText = request.get("resumeText");
        String jobDescription = request.get("jobDescription");
        if (resumeText == null || resumeText.trim().isEmpty()) {
            return Result.error(400, "简历内容不能为空");
        }
        if (jobDescription == null || jobDescription.trim().isEmpty()) {
            return Result.error(400, "岗位描述不能为空");
        }
        currentUserId();
        String result = jobAnalysisService.diagnoseGap(resumeText, jobDescription);
        return Result.success(result);
    }

    /**
     * 求职信/申请邮件/内推私信生成
     * POST /api/job/letter
     * Body: {"resumeText": "...", "jobDescription": "...", "type": "coverLetter|email|referral"}
     */
    @Operation(summary = "求职信/申请邮件/内推私信生成")
    @PostMapping("/letter")
    public Result<String> letter(@RequestBody Map<String, String> request) {
        String resumeText = request.get("resumeText");
        String jobDescription = request.get("jobDescription");
        String type = request.getOrDefault("type", "coverLetter");

        if (resumeText == null || resumeText.trim().isEmpty()) {
            return Result.error(400, "简历内容不能为空");
        }
        if (jobDescription == null || jobDescription.trim().isEmpty()) {
            return Result.error(400, "岗位描述不能为空");
        }
        currentUserId();
        String result = jobAnalysisService.generateLetter(resumeText, jobDescription, type);
        return Result.success(result);
    }

    /**
     * AI 抽取结果是否为「三个字段全空」（v1.62.1）。
     *
     * <p>不能只判断字符串是否为空——模型返回的是一段 JSON 文本，字段全空时它是
     * {@code {"jobTitle":"","company":"","jobDescription":""}}，字符串本身并不为空。
     *
     * <p>先剥掉可能的 Markdown 代码块围栏（复用 {@link JsonRepairUtil#stripMarkdownFence}）。
     * <b>解析不出来时返回 false</b>：那种情况交给上层既有的 500 分支，
     * 不在这里把「解析失败」误判成「不是岗位页」。
     */
    private static boolean isBlankExtraction(String json) {
        if (json == null || json.trim().isEmpty()) {
            return true;
        }
        try {
            JsonNode node = MAPPER.readTree(JsonRepairUtil.stripMarkdownFence(json));
            return isBlank(node.path("jobTitle").asText())
                    && isBlank(node.path("company").asText())
                    && isBlank(node.path("jobDescription").asText());
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
