package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.service.InterviewService;
import com.example.interview.util.PromptSanitizer;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import reactor.core.Disposable;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
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
     * SSE 并发限流：限制同时进行的流式连接数，防止虚拟线程池无界扩张导致 OOM
     * - 上限通过 app.sse.max-concurrent 配置，默认 20
     * - 超出则返回 503，前端提示用户稍后重试
     */
    @Value("${app.sse.max-concurrent:20}")
    private int sseMaxConcurrent;

    private Semaphore sseSemaphore;

    @PostConstruct
    private void initSemaphore() {
        this.sseSemaphore = new Semaphore(sseMaxConcurrent, true);
        log.info("SSE 并发限流初始化: max-concurrent={}", sseMaxConcurrent);
    }

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

        String userId = currentUserId();
        String result = interviewService.generateQuestions(userId, resumeText, jobDescription, count);
        return Result.success(result);
    }

    /** 从 SecurityContext 获取当前登录用户 ID（JWT subject） */
    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            throw new IllegalStateException("未认证用户");
        }
        return auth.getPrincipal().toString();
    }

    /**
     * Prompt 注入防御：委托 {@link PromptSanitizer#sanitize(String)}
     * v1.9 起统一使用工具类，消除 9 处重复 private 方法
     */
    private String sanitizePromptInput(String input) {
        return PromptSanitizer.sanitize(input);
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
        // 保存 Disposable 以便客户端断开时取消订阅，防止资源泄漏
        final Disposable[] disposableHolder = new Disposable[1];

        // 尝试获取并发令牌，超出 max-concurrent 则返回 503
        if (!sseSemaphore.tryAcquire()) {
            // 注意：此处未注册 onCompletion，emitter.complete() 不会触发 release，避免 double release
            try {
                emitter.send(SseEmitter.event().name("error").data("当前在线用户较多，请稍后重试"));
            } catch (IOException ignored) {
            }
            emitter.complete();
            return emitter;
        }

        // 客户端断开或超时时取消 AI 订阅并释放令牌（仅在成功获取令牌后注册）
        emitter.onCompletion(() -> {
            heartbeatRunning.set(false);
            sseSemaphore.release();
            if (disposableHolder[0] != null && !disposableHolder[0].isDisposed()) {
                disposableHolder[0].dispose();
            }
        });
        emitter.onTimeout(() -> {
            heartbeatRunning.set(false);
            sseSemaphore.release();
            if (disposableHolder[0] != null && !disposableHolder[0].isDisposed()) {
                disposableHolder[0].dispose();
            }
            emitter.complete();
        });
        emitter.onError(e -> {
            heartbeatRunning.set(false);
            sseSemaphore.release();
            if (disposableHolder[0] != null && !disposableHolder[0].isDisposed()) {
                disposableHolder[0].dispose();
            }
        });

        sseExecutor.submit(() -> {
            try {
                // 立即发送开始事件，给前端即时反馈
                emitter.send(SseEmitter.event().name("start").data(""));
                heartbeatRunning.set(true);

                // 启动心跳：每 15s 发送注释行，防止代理超时
                var heartbeatFuture = heartbeat.scheduleAtFixedRate(() -> {
                    if (!heartbeatRunning.get()) return;
                    try {
                        emitter.send(SseEmitter.event().comment("ping"));
                    } catch (IOException e) {
                        heartbeatRunning.set(false);
                    }
                }, 15, 15, TimeUnit.SECONDS);

                // 构建 prompt：精简，聚焦问题本身，加快响应；用户输入经 sanitize 防注入
                String safeQuestion = sanitizePromptInput(question);
                String safeContext = context == null ? "" : sanitizePromptInput(context);
                String prompt;
                if (safeContext.isBlank()) {
                    prompt = "请简洁回答以下面试题，控制在 300 字以内，突出要点：\n\n" + safeQuestion;
                } else {
                    prompt = "请简洁回答以下面试题，控制在 300 字以内，突出要点：\n\n"
                            + "【背景】" + safeContext + "\n\n【问题】" + safeQuestion;
                }

                // 使用 Spring AI stream() 逐 token 推送，保存 Disposable 以便取消
                disposableHolder[0] = chatClient.prompt()
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
