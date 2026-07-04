package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.service.ResumeAnalysisService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 简历分析接口
 */
@RestController
@RequestMapping("/api/resume")
@CrossOrigin(origins = "*")
public class ResumeController {

    @Autowired
    private ResumeAnalysisService resumeAnalysisService;

    /**
     * 分析简历
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

        try {
            String result = resumeAnalysisService.analyze(resumeText, targetJob);
            return Result.success(result);
        } catch (Exception e) {
            return Result.error("简历分析失败：" + e.getMessage());
        }
    }
}
