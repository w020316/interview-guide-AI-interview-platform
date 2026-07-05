package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.entity.ResumeEntity;
import com.example.interview.service.ResumeAnalysisService;
import com.example.interview.service.ResumeParseService;
import com.example.interview.service.ResumeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
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
    public Result<String> upload(
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
            return Result.error(400, "仅支持 PDF、TXT 格式的简历文件（Word 请转换为 PDF）");
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
            // 解析 + 分析，返回 AI JSON
            String analysisJson = resumeParseService.parseAndAnalyze(currentUserId(), file, targetJob);
            // 解析后的纯文本用于持久化（再解析一次以拿到 resumeText，避免修改 parseAndAnalyze 签名）
            // 这里简化处理：直接持久化上传文件名 + 分析结果
            try {
                String preview = originalFilename;
                resumeService.saveResume(currentUserId(), "[上传文件] " + preview, targetJob, analysisJson);
            } catch (Exception e) {
                log.warn("简历上传结果持久化失败：{}", e.getMessage());
            }
            return Result.success(analysisJson);
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
}

