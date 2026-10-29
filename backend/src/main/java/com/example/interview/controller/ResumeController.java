package com.example.interview.controller;

import com.example.interview.common.RateLimitExceededException;
import com.example.interview.common.Result;
import com.example.interview.entity.ResumeEntity;
import com.example.interview.service.ResumeAnalysisService;
import com.example.interview.service.ResumeExportCheckService;
import com.example.interview.service.ResumeParseService;
import com.example.interview.service.ResumeService;
import com.example.interview.util.PerUserRateLimiter;
import com.example.interview.util.SsrUrlValidator;
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

    /**
     * 允许的**图片**扩展名（v1.45.0）。
     *
     * <p>为什么简历上传要收图片：手机上没有可靠的「从别的 App 取文件」方案
     * （微信内置浏览器拦 scheme、filehelper 网页版只在电脑可用、wx.chooseMessageFile 需公众号），
     * 而**截图在任何 App 里都做得到**。所以「截图 → 视觉模型识别 → 走既有分析链路」
     * 是手机上最通用的一条路。
     */
    private static final Set<String> ALLOWED_IMAGE_EXTS =
            Set.of(".png", ".jpg", ".jpeg", ".webp", ".gif");

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

    /** 简历图片识别（截图/拍照 → 文本），未配置时该入口会给出明确提示 */
    @Autowired
    private com.example.interview.service.ResumeImageOcrService resumeImageOcrService;

    @Autowired
    private ResumeService resumeService;

    /** 导出体检（纯本地 PDFBox 解析，不调用 AI） */
    @Autowired
    private ResumeExportCheckService resumeExportCheckService;

    /**
     * 导出体检按用户限流（20 次/分钟）。
     *
     * <p>不挂进 AI 限流链路：它不消耗模型额度，但解析 PDF 有 CPU/内存成本，
     * 仍需要一道闸门防止脚本无限上传。限流出口统一走
     * {@link RateLimitExceededException} → 真 429，与项目既有约定一致。
     */
    private final PerUserRateLimiter exportCheckLimiter = new PerUserRateLimiter(20, 60_000L);

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
        boolean isImage = ALLOWED_IMAGE_EXTS.stream().anyMatch(lower::endsWith);
        if (!isImage && !ALLOWED_EXTS.stream().anyMatch(lower::endsWith)) {
            return Result.error(400,
                    "仅支持 PDF / HTML / MD / TXT 文件，或简历截图（PNG / JPG / WEBP）");
        }

        // Content-Type 校验：图片走图片白名单（真实的格式校验在 OCR 服务里按文件头做）
        String contentType = file.getContentType();
        if (contentType != null) {
            String ct = contentType.toLowerCase();
            boolean ctOk = isImage ? ct.startsWith("image/") : ALLOWED_CT.contains(ct);
            if (!ctOk) {
                log.warn("非法 Content-Type 上传: {} (filename={})", contentType, originalFilename);
                return Result.error(400, "文件类型不受支持");
            }
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
            // 1. 解析为纯文本：图片走视觉识别（截图是手机上最通用的取件方式），其余走原有解析
            String resumeText;
            boolean ocrTruncated = false;
            if (isImage) {
                try {
                    var ocr = resumeImageOcrService.extractText(file);
                    resumeText = ocr.text();
                    ocrTruncated = ocr.truncated();
                } catch (IllegalStateException e) {
                    // 识别类失败给明确原因（未配置 / 不是图片 / 上游错误），
                    // 不落进下面那句笼统的「文件是否损坏」
                    return Result.error(502, e.getMessage());
                }
            } else {
                resumeText = resumeParseService.parseToText(file);
            }
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
            if (isImage) {
                // 让前端知道这份文本来自截图识别，并如实告知是否被输出上限截断
                payload.put("fromImage", "true");
                if (ocrTruncated) {
                    payload.put("imageTruncated", "true");
                }
            }
            return Result.success(payload);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("简历解析失败 filename={}", originalFilename, e);
            return Result.error("简历解析失败，请检查文件是否损坏或重试");
        }
    }

    /**
     * 导出体检：检查「导出的 PDF 能不能被招聘系统读出来」（v1.85.0）
     * POST /api/resume/export-check
     * Form-data: file=&lt;导出的 PDF&gt;
     *
     * <p>只接受 PDF：这项能力的检查对象是**导出产物**（浏览器打印生成的 PDF），
     * 不是源文件。粘贴文本/上传 docx 分析那条链路已有独立入口。
     *
     * <p>结果是一份确定性报告（页数 / 文字层 / 联系方式 / 关键板块 / 时间信息 / 字符完整度），
     * 同样的文件永远得到同样的结论；不经过任何 AI，简历原文也不会离开服务端。
     */
    @PostMapping("/export-check")
    public Result<ResumeExportCheckService.ExportCheckReport> exportCheck(
            @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return Result.error(400, "请上传导出的 PDF 文件");
        }
        if (file.getSize() > ResumeExportCheckService.MAX_FILE_BYTES) {
            return Result.error(400, "文件大小不能超过 10MB");
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            return Result.error(400, "文件名不能为空");
        }
        if (!originalFilename.toLowerCase().endsWith(".pdf")) {
            return Result.error(400, "导出体检只支持 PDF：请先点「导出 PDF」，再把保存下来的文件上传体检");
        }
        String contentType = file.getContentType();
        if (contentType != null && !contentType.toLowerCase().startsWith("application/pdf")
                && !"application/octet-stream".equalsIgnoreCase(contentType)) {
            return Result.error(400, "文件类型不受支持");
        }
        if (!hasPdfMagic(file)) {
            return Result.error(400, "文件内容不是合法的 PDF");
        }
        if (!exportCheckLimiter.allow(currentUserId())) {
            throw new RateLimitExceededException("体检太频繁了，请一分钟后再试");
        }
        return Result.success(resumeExportCheckService.check(file));
    }

    /** PDF 文件头校验（%PDF-），防止改了后缀的非 PDF 文件进入解析器 */
    private static boolean hasPdfMagic(MultipartFile file) {
        try (java.io.InputStream is = file.getInputStream()) {
            byte[] head = new byte[5];
            int read = is.read(head);
            return read == 5 && "%PDF-".equals(new String(head));
        } catch (IOException e) {
            return false;
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
     * 删除一份简历（G2，2026-10-18）
     * DELETE /api/resume/{id}?cascade=false
     *
     * <p>此前简历**只增不删**：分析了几十份之后列表无法清理，用户只能靠「最新在前」硬扛。
     *
     * <p>默认保守：若该简历已派生出面试会话，返回 409 并带上会话数量，
     * 让用户明确知道「删这份简历会牵连到面试记录」；只有显式 {@code cascade=true}
     * 才连带删除。返回值里带 {@code deletedSessions} 供前端如实提示。
     */
    @DeleteMapping("/{id}")
    public Result<Map<String, Object>> delete(@PathVariable Long id,
                                              @RequestParam(required = false, defaultValue = "false") boolean cascade) {
        int deletedSessions = resumeService.deleteForUser(id, currentUserId(), cascade);
        Map<String, Object> data = new HashMap<>();
        data.put("deletedSessions", deletedSessions);
        return Result.success(data);
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

        // URL 安全校验（防 SSRF）：协议/主机端口/内网与元数据网段统一校验（v1.31.4 抽出共享校验器）
        SsrUrlValidator.Result valid = SsrUrlValidator.validate(url);
        if (!valid.ok) {
            return Result.error(400, valid.message);
        }
        url = valid.normalizedUrl;

        String userId = currentUserId();
        try {
            // 抓取 URL 页面（限制 10 秒超时，模拟浏览器 UA，禁止跟随重定向防 SSRF 绕过）
            org.jsoup.nodes.Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(10_000)
                    .maxBodySize(5 * 1024 * 1024) // 5MB 上限
                    .followRedirects(false)
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

