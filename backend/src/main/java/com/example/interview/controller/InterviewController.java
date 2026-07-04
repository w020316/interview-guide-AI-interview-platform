package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.service.InterviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * AI 模拟面试接口
 */
@RestController
@RequestMapping("/api/interview")
@CrossOrigin(origins = "*")
public class InterviewController {

    @Autowired
    private InterviewService interviewService;

    /**
     * 生成面试题
     * POST /api/interview/questions
     * Body: {"resumeText": "...", "jobDescription": "...", "count": 5}
     */
    @PostMapping("/questions")
    public Result<String> generateQuestions(@RequestBody Map<String, Object> request) {
        String resumeText = (String) request.get("resumeText");
        String jobDescription = (String) request.get("jobDescription");
        int count = (int) request.getOrDefault("count", 5);

        if (resumeText == null || jobDescription == null) {
            return Result.error(400, "简历和岗位描述不能为空");
        }

        try {
            String result = interviewService.generateQuestions(resumeText, jobDescription, count);
            return Result.success(result);
        } catch (Exception e) {
            return Result.error("面试题生成失败：" + e.getMessage());
        }
    }

    /**
     * 评估用户回答
     * POST /api/interview/evaluate
     * Body: {"question": "...", "userAnswer": "...", "referenceAnswer": "..."}
     */
    @PostMapping("/evaluate")
    public Result<String> evaluateAnswer(@RequestBody Map<String, String> request) {
        String question = request.get("question");
        String userAnswer = request.get("userAnswer");
        String referenceAnswer = request.getOrDefault("referenceAnswer", "");

        if (question == null || userAnswer == null) {
            return Result.error(400, "问题和回答不能为空");
        }

        try {
            String result = interviewService.evaluateAnswer(question, userAnswer, referenceAnswer);
            return Result.success(result);
        } catch (Exception e) {
            return Result.error("回答评估失败：" + e.getMessage());
        }
    }
}
