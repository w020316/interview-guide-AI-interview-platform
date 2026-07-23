package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.entity.ResumeEntity;
import com.example.interview.service.ResumeAnalysisService;
import com.example.interview.service.ResumeParseService;
import com.example.interview.service.ResumeService;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 简历分析接口
 * - POST /api/resume/analyze 粘贴文本分析
 * - POST /api/resume/upload  上传 PDF/TXT 分析
 * - GET  /api/resume/history 简历历史列表
 * - GET  /api/resume/{id}    简历详情
 */
@RestController
@RequestMapping("/api/resume")
public class ResumeController {

    private static final Logger log = LoggerFactory.getLogger(ResumeController.class);

    /** 允许的文件扩展名 */
    private static final Set<String> ALLOWED_EXTS = Set.of(".pdf", ".txt", ".html", ".htm", ".md", ".markdown");

    /** 允许的 Content-Type */
    private static final Set<String> ALLOWED_CT = Set.of(
            "application/pdf",
            "text/plain",
            "text/html",
            "text/markdown",
            "application/octet-stream" // 部分浏览器上传 txt/md 时为此类型
    );

    @Autowired
    private ResumeAnalysisService resumeAnalysisService;

    @Autowired
    private ResumeParseService resumeParseService;

    @Autowired
    private ResumeService resumeService;

    /** 从 SecurityContext 获取当前登录用户 ID */
    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            throw new IllegalStateException("未认证用户");
        }
        return auth.getPrincipal().toString();
    }

    /**
     * 分析简历（纯文本）
     * POST /api/resume/analyze
     * Body: {"resumeText": "...", "targetJob": "目标岗位"}
     */
    @PostMapping("/analyze")
    public Result<String> analyze(@RequestBody Map<String, String> request) {
        String resumeText = request.get("resumeText");
        String targetJob = request.getOrDefault("targetJob", "通用岗位");

        if (resumeText == null || resumeText.trim().isEmpty()) {
            return Result.error(400, "简历内容不能为空");
        }

        String result = resumeAnalysisService.analyze(currentUserId(), resumeText, targetJob);
        // 持久化到数据库（失败不影响返回分析结果给用户）
        try {
            resumeService.saveResume(currentUserId(), resumeText, targetJob, result);
        } catch (Exception e) {
            log.warn("简历分析结果持久化失败：{}", e.getMessage());
        }
        return Result.success(result);
    }

    /**
     * 上传并解析简历文件（PDF / TXT）
     * POST /api/resume/upload
     * Form-data: file=<文件>, targetJob=<岗位>
     */
    @PostMapping("/upload")
    public Result<Map<String, String>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "targetJob", defaultValue = "通用岗位") String targetJob) {

        if (file == null || file.isEmpty()) {
            return Result.error(400, "请上传简历文件");
        }

        // 文件大小校验（10MB）
        if (file.getSize() > 10 * 1024 * 1024) {
            return Result.error(400, "文件大小不能超过 10MB");
        }

        // 文件名与扩展名校验
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            return Result.error(400, "文件名不能为空");
        }
        String lower = originalFilename.toLowerCase();
        if (!ALLOWED_EXTS.stream().anyMatch(lower::endsWith)) {
            return Result.error(400, "仅支持 PDF / HTML / MD / TXT 格式的简历文件（Word 请转换为 PDF）");
        }

        // Content-Type 校验
        String contentType = file.getContentType();
        if (contentType != null && !ALLOWED_CT.contains(contentType.toLowerCase())) {
            log.warn("非法 Content-Type 上传: {} (filename={})", contentType, originalFilename);
            return Result.error(400, "文件类型不受支持");
        }

        // PDF 文件 magic bytes 校验（%PDF-）
        if (lower.endsWith(".pdf")) {
            try (java.io.InputStream is = file.getInputStream()) {
                byte[] head = new byte[5];
                int read = is.read(head);
                if (read < 5 || !"%PDF-".equals(new String(head))) {
                    return Result.error(400, "文件内容不是合法的 PDF");
                }
            } catch (IOException e) {
                log.error("读取文件头失败", e);
                return Result.error(400, "文件读取失败");
            }
        }

        try {
            // 1. 解析文件为纯文本
            String resumeText = resumeParseService.parseToText(file);
            // 2. 调用 AI 分析
            String analysisJson = resumeAnalysisService.analyze(currentUserId(), resumeText, targetJob);
            // 3. 持久化（保存真实简历文本，便于后续优化与回看）
            try {
                resumeService.saveResume(currentUserId(), resumeText, targetJob, analysisJson);
            } catch (Exception e) {
                log.warn("简历上传结果持久化失败：{}", e.getMessage());
            }
            // 4. 返回 analysis + resumeText（前端需要 resumeText 调用 /optimize 生成优化简历）
            Map<String, String> payload = new HashMap<>();
            payload.put("analysis", analysisJson);
            payload.put("resumeText", resumeText);
            return Result.success(payload);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("简历解析失败 filename={}", originalFilename, e);
            return Result.error("简历解析失败，请检查文件是否损坏或重试");
        }
    }

    /**
     * 当前用户的简历历史列表
     * GET /api/resume/history
     */
    @GetMapping("/history")
    public Result<List<ResumeEntity>> history() {
        return Result.success(resumeService.listByUser(currentUserId()));
    }

    /**
     * 简历详情（仅本人）
     * GET /api/resume/{id}
     */
    @GetMapping("/{id}")
    public Result<ResumeEntity> getById(@PathVariable Long id) {
        return Result.success(resumeService.getByIdAndUser(id, currentUserId()));
    }

    /**
     * 基于分析结果生成优化版简历（Markdown 格式）
     * POST /api/resume/optimize
     * Body: {"resumeText": "...", "targetJob": "...", "analysis": "..."}
     *
     * 返回优化后的 Markdown 简历，前端可直接下载为 .md 文件或渲染预览
     */
    @PostMapping("/optimize")
    public Result<String> optimize(@RequestBody Map<String, String> request) {
        String userId = currentUserId();
        String resumeText = request.get("resumeText");
        String targetJob = request.getOrDefault("targetJob", "通用岗位");
        String analysis = request.get("analysis");

        // resumeText 为空时，从数据库最近一次简历兜底（兼容老前端或刷新页面丢失状态）
        if (resumeText == null || resumeText.trim().isEmpty()) {
            try {
                List<ResumeEntity> history = resumeService.listByUser(userId);
                if (!history.isEmpty()) {
                    ResumeEntity latest = history.get(0);
                    resumeText = latest.getContent();
                    if (targetJob == null || targetJob.isBlank() || "通用岗位".equals(targetJob)) {
                        targetJob = latest.getTargetJob();
                    }
                    log.info("optimize 接口 resumeText 为空，从数据库最近简历兜底 userId={} resumeId={}",
                            userId, latest.getId());
                }
            } catch (Exception e) {
                log.warn("从数据库兜底 resumeText 失败：{}", e.getMessage());
            }
        }

        if (resumeText == null || resumeText.trim().isEmpty()) {
            return Result.error(400, "简历内容不能为空，请先上传简历或粘贴文本完成分析");
        }
        if (analysis == null || analysis.trim().isEmpty()) {
            return Result.error(400, "请先完成简历分析，再生成优化版简历");
        }

        String optimized = resumeAnalysisService.generateOptimizedResume(
                userId, resumeText, targetJob, analysis);
        return Result.success(optimized);
    }

    /**
     * 从 URL 导入简历（iOS/移动端"从其他平台导入"功能）
     * POST /api/resume/import-url
     * Body: {"url": "https://...", "targetJob": "..."}
     *
     * 后端抓取 URL 页面 HTML，提取纯文本后调用 AI 分析。
     * 支持：在线简历页面（超级简历/GitHub 主页/个人博客等公开 URL）
     */
    @PostMapping("/import-url")
    public Result<Map<String, String>> importFromUrl(@RequestBody Map<String, String> request) {
        String url = request.get("url");
        String targetJob = request.getOrDefault("targetJob", "通用岗位");

        if (url == null || url.trim().isEmpty()) {
            return Result.error(400, "URL 不能为空");
        }

        // URL 安全校验：仅允许 http/https
        String lowerUrl = url.toLowerCase().trim();
        if (!lowerUrl.startsWith("http://") && !lowerUrl.startsWith("https://")) {
            return Result.error(400, "请输入有效的 URL（以 http:// 或 https:// 开头）");
        }

        // 防止 SSRF：禁止访问内网地址
        if (lowerUrl.contains("localhost") || lowerUrl.contains("127.0.0.1")
                || lowerUrl.contains("192.168.") || lowerUrl.contains("10.")
                || lowerUrl.contains("172.16.") || lowerUrl.contains("0.0.0.0")) {
            return Result.error(400, "不支持访问内网地址");
        }

        String userId = currentUserId();
        try {
            // 抓取 URL 页面（限制 10 秒超时，模拟浏览器 UA）
            org.jsoup.nodes.Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(10_000)
                    .maxBodySize(5 * 1024 * 1024) // 5MB 上限
                    .get();

            // 提取纯文本
            String resumeText = Jsoup.clean(doc.html(), Safelist.none())
                    .replaceAll("\\n{3,}", "\n\n")
                    .trim();

            if (resumeText == null || resumeText.length() < 50) {
                return Result.error(400, "页面内容过少，可能不是有效的简历页面");
            }

            // 调用 AI 分析
            String analysisJson = resumeAnalysisService.analyze(userId, resumeText, targetJob);

            // 持久化
            try {
                resumeService.saveResume(userId, resumeText, targetJob, analysisJson);
            } catch (Exception e) {
                log.warn("URL 导入简历持久化失败：{}", e.getMessage());
            }

            Map<String, String> payload = new HashMap<>();
            payload.put("analysis", analysisJson);
            payload.put("resumeText", resumeText);
            return Result.success(payload);
        } catch (java.net.SocketTimeoutException e) {
            return Result.error(400, "网页访问超时，请检查 URL 是否可公开访问");
        } catch (org.jsoup.HttpStatusException e) {
            return Result.error(400, "网页返回错误状态码：" + e.getStatusCode());
        } catch (Exception e) {
            log.error("URL 导入简历失败 url={}", url, e);
            return Result.error(400, "导入失败，请确认 URL 可公开访问后重试");
        }
    }
}

