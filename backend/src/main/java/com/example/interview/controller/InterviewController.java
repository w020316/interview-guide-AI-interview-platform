package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.service.InterviewService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 模拟面试接口
 */
@RestController
@RequestMapping("/api/interview")
public class InterviewController {

    @Autowired
    private InterviewService interviewService;

    @Autowired
    private ChatClient chatClient;

    /** SSE 推送使用独立线程池，避免阻塞 Tomcat 工作线程 */
    private final ExecutorService sseExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 生成面试题
     * POST /api/interview/questions
     * Body: {"resumeText": "...", "jobDescription": "...", "count": 5}
     */
    @PostMapping("/questions")
    public Result<String> generateQuestions(@RequestBody Map<String, Object> request) {
        String resumeText = (String) request.get("resumeText");
        String jobDescription = (String) request.get("jobDescription");
        // 安全类型转换：避免 ClassCastException
        int count = 5;
        Object countObj = request.get("count");
        if (countObj instanceof Number n) {
            count = n.intValue();
        }
        // 范围校验
        if (count < 1) count = 1;
        if (count > 20) count = 20;

        if (resumeText == null || jobDescription == null) {
            return Result.error(400, "简历和岗位描述不能为空");
        }

        String result = interviewService.generateQuestions(resumeText, jobDescription, count);
        return Result.success(result);
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

        String result = interviewService.evaluateAnswer(question, userAnswer, referenceAnswer);
        return Result.success(result);
    }

    /**
     * SSE 流式回答面试问题
     * POST /api/interview/ask/stream
     * Body: {"question": "...", "context": "..."}
     * 前端通过 EventSource 或 fetch + ReadableStream 接收
     */
    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAnswer(@RequestBody Map<String, String> request) {
        String question = request.get("question");
        String context = request.getOrDefault("context", "");

        if (question == null || question.isBlank()) {
            // SseEmitter 不能直接返回错误体，用超短超时触发 onError
            SseEmitter err = new SseEmitter(0L);
            try {
                err.send(SseEmitter.event().name("error").data("question 不能为空"));
            } catch (IOException e) {
                // 仅记录日志，不中断流程
            }
            err.complete();
            return err;
        }

        // 超时设置 120 秒，覆盖长回答场景
        SseEmitter emitter = new SseEmitter(120_000L);

        sseExecutor.submit(() -> {
            try {
                String prompt = context.isBlank()
                        ? question
                        : String.format("【背景】\n%s\n\n【问题】\n%s", context, question);

                // 使用 Spring AI stream() 逐 token 推送
                chatClient.prompt()
                        .user(prompt)
                        .stream()
                        .content()
                        .doOnError(emitter::completeWithError)
                        .doOnComplete(emitter::complete)
                        .subscribe(token -> {
                            try {
                                emitter.send(SseEmitter.event().name("token").data(token));
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        });
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }
}
