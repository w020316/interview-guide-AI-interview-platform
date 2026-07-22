package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.service.JobAnalysisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

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

    @Autowired
    private JobAnalysisService jobAnalysisService;

    /** 从 SecurityContext 获取当前登录用户 ID */
    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            throw new IllegalStateException("未认证用户");
        }
        return auth.getPrincipal().toString();
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
}
