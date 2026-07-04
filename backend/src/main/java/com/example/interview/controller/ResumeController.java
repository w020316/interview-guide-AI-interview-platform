package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.service.ResumeAnalysisService;
import com.example.interview.service.ResumeParseService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 简历分析接口
 */
@RestController
@RequestMapping("/api/resume")
public class ResumeController {

    @Autowired
    private ResumeAnalysisService resumeAnalysisService;

    @Autowired
    private ResumeParseService resumeParseService;

    /**
     * 分析简历（纯文本）
     * POST /api/resume/analyze
     * Body: {"resumeText": "...", "targetJob": "Java 后端开发"}
     */
    @PostMapping("/analyze")
    public Result<String> analyze(@RequestBody Map<String, String> request) {
        String resumeText = request.get("resumeText");
        String targetJob = request.getOrDefault("targetJob", "Java 后端开发");

        if (resumeText == null || resumeText.trim().isEmpty()) {
            return Result.error(400, "简历内容不能为空");
        }

        String result = resumeAnalysisService.analyze(resumeText, targetJob);
        return Result.success(result);
    }

    /**
     * 上传并解析简历文件（PDF / DOCX / TXT）
     * POST /api/resume/upload
     * Form-data: file=<文件>, targetJob=<岗位>
     */
    @PostMapping("/upload")
    public Result<String> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "targetJob", defaultValue = "Java 后端开发") String targetJob) {

        if (file == null || file.isEmpty()) {
            return Result.error(400, "请上传简历文件");
        }

        // 文件大小校验（10MB）
        if (file.getSize() > 10 * 1024 * 1024) {
            return Result.error(400, "文件大小不能超过 10MB");
        }

        // 限制文件类型：仅 PDF / TXT（DOCX 暂不支持）
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            return Result.error(400, "文件名不能为空");
        }
        String lower = originalFilename.toLowerCase();
        if (!lower.endsWith(".pdf") && !lower.endsWith(".txt")) {
            return Result.error(400, "仅支持 PDF、TXT 格式的简历文件（Word 请转换为 PDF）");
        }

        try {
            String result = resumeParseService.parseAndAnalyze(file, targetJob);
            return Result.success(result);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            return Result.error("简历解析失败：" + e.getMessage());
        }
    }
}
