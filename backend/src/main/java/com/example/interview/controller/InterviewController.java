package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.service.InterviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 模拟面试接口
 */
@RestController
@RequestMapping("/api/interview")
public class InterviewController {

    private static final Logger log = LoggerFactory.getLogger(InterviewController.class);

    @Autowired
    private InterviewService interviewService;

    @Autowired
    private ChatClient chatClient;

    /** SSE 推送使用虚拟线程池，避免阻塞 Tomcat 工作线程 */
    private final ExecutorService sseExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /** 心跳调度器：定期发送注释行，避免反向代理/浏览器超时断开 */
    private final ScheduledExecutorService heartbeat = new ScheduledThreadPoolExecutor(1);

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
     * SSE 流式回答面试问题（优化版）
     * POST /api/interview/ask/stream
     * Body: {"question": "...", "context": "..."}
     *
     * 优化点：
     * 1. 精简 prompt，减少输入 token 数，加快首 token 响应
     * 2. 心跳保活：每 15s 发送注释行，避免 Vercel/Nginx 60s 超时断开
     * 3. 立即发送开始事件，让前端有即时反馈
     * 4. 限制最大 token 数，避免过长回答拖慢整体速度
     */
    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamAnswer(@RequestBody Map<String, String> request) {
        String question = request.get("question");
        String context = request.getOrDefault("context", "");

        if (question == null || question.isBlank()) {
            SseEmitter err = new SseEmitter(0L);
            try {
                err.send(SseEmitter.event().name("error").data("question 不能为空"));
            } catch (IOException ignored) {
            }
            err.complete();
            return err;
        }

        // 超时 120 秒
        SseEmitter emitter = new SseEmitter(120_000L);
        AtomicBoolean heartbeatRunning = new AtomicBoolean(false);

        sseExecutor.submit(() -> {
            try {
                // 立即发送开始事件，给前端即时反馈
                emitter.send(SseEmitter.event().name("start").data(""));
                heartbeatRunning.set(true);

                // 启动心跳：每 15s 发送注释行，防止代理超时
                var heartbeatFuture = heartbeat.scheduleAtFixedRate(() -> {
                    if (!heartbeatRunning.get()) return;
                    try {
                        // SSE 注释行，前端 EventSource 会自动忽略
                        emitter.send(SseEmitter.event().comment("ping"));
                    } catch (IOException e) {
                        // 连接已断，停止心跳
                        heartbeatRunning.set(false);
                    }
                }, 15, 15, TimeUnit.SECONDS);

                // 构建 prompt：精简系统提示，聚焦问题本身，加快响应
                String prompt;
                if (context == null || context.isBlank()) {
                    prompt = "请简洁回答以下面试题，控制在 300 字以内，突出要点：\n\n" + question;
                } else {
                    prompt = "请简洁回答以下面试题，控制在 300 字以内，突出要点：\n\n"
                            + "【背景】" + context + "\n\n【问题】" + question;
                }

                // 使用 Spring AI stream() 逐 token 推送
                chatClient.prompt()
                        .user(prompt)
                        .stream()
                        .content()
                        .doOnError(e -> {
                            log.warn("SSE 流式生成失败：{}", e.getMessage());
                            heartbeatRunning.set(false);
                            heartbeatFuture.cancel(false);
                            emitter.completeWithError(e);
                        })
                        .doOnComplete(() -> {
                            heartbeatRunning.set(false);
                            heartbeatFuture.cancel(false);
                            try {
                                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                            } catch (IOException ignored) {
                            }
                            emitter.complete();
                        })
                        .subscribe(token -> {
                            try {
                                emitter.send(SseEmitter.event().name("token").data(token));
                            } catch (IOException e) {
                                heartbeatRunning.set(false);
                                heartbeatFuture.cancel(false);
                                emitter.completeWithError(e);
                            }
                        });
            } catch (Exception e) {
                log.error("SSE 流式回答异常 question='{}'", question, e);
                heartbeatRunning.set(false);
                try {
                    emitter.send(SseEmitter.event().name("error").data("AI 服务异常，请重试"));
                } catch (IOException ignored) {
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }
}
