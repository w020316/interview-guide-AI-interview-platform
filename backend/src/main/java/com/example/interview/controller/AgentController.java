package com.example.interview.controller;

import com.example.interview.common.Result;
import com.example.interview.entity.AgentConversationEntity;
import com.example.interview.service.agent.AgentService;
import com.example.interview.util.PromptSanitizer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 智能体接口（Career Copilot）
 *
 * - POST /api/agent/chat/stream  流式对话（SSE：meta/start/token/done/error 事件）
 * - GET  /api/agent/conversations           会话列表
 * - GET  /api/agent/conversations/{id}/messages  会话历史消息
 * - DELETE /api/agent/conversations/{id}    删除会话
 *
 * userId 一律从 JWT 提取（防 IDOR）；SSE 并发受信号量保护（超出返回 error 事件）。
 */
@Tag(name = "智能体", description = "Career Copilot 求职智能体对话")
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    /** SSE 会话准备/事件推送专用线程池（虚拟线程，随用随建） */
    private final java.util.concurrent.ExecutorService sseExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            throw new IllegalStateException("未认证用户");
        }
        return auth.getPrincipal().toString();
    }

    /**
     * 流式对话
     * Body: { "message": "...", "conversationId": 1（可选，不传则新建会话） }
     */
    @Operation(summary = "智能体流式对话")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody Map<String, Object> request) {
        String message = request.get("message") == null ? "" : request.get("message").toString();
        Long conversationId = request.get("conversationId") == null ? null
                : Long.valueOf(request.get("conversationId").toString());
        String userId = currentUserId();

        SseEmitter emitter = new SseEmitter(180_000L);
        AtomicBoolean heartbeatRunning = new AtomicBoolean(false);
        final Disposable[] disposableHolder = new Disposable[1];

        if (message.isBlank()) {
            try {
                emitter.send(SseEmitter.event().name("error").data("消息不能为空"));
            } catch (IOException ignored) {
            }
            emitter.complete();
            return emitter;
        }

        // SSE 并发保护
        if (!agentService.tryAcquire()) {
            try {
                emitter.send(SseEmitter.event().name("error").data("当前在线用户较多，请稍后重试"));
            } catch (IOException ignored) {
            }
            emitter.complete();
            return emitter;
        }

        emitter.onCompletion(() -> {
            heartbeatRunning.set(false);
            agentService.release();
            if (disposableHolder[0] != null && !disposableHolder[0].isDisposed()) {
                disposableHolder[0].dispose();
            }
        });
        emitter.onTimeout(() -> {
            heartbeatRunning.set(false);
            agentService.release();
            if (disposableHolder[0] != null && !disposableHolder[0].isDisposed()) {
                disposableHolder[0].dispose();
            }
            emitter.complete();
        });
        emitter.onError(e -> {
            heartbeatRunning.set(false);
            agentService.release();
            if (disposableHolder[0] != null && !disposableHolder[0].isDisposed()) {
                disposableHolder[0].dispose();
            }
        });

        sseExecutor.submit(() -> {
            try {
                // 会话解析/创建（校验在 executor 线程做，含 DB 操作）
                AgentService.AgentStreamSession session;
                try {
                    session = agentService.streamChat(userId, conversationId, PromptSanitizer.sanitize(message));
                } catch (Exception e) {
                    log.warn("智能体会话解析失败：{}", e.getMessage());
                    emitter.send(SseEmitter.event().name("error").data("会话创建失败，请重试"));
                    emitter.complete();
                    return;
                }

                // 通知前端会话 ID（新建会话时前端需要保存）
                emitter.send(SseEmitter.event().name("meta").data(
                        "{\"conversationId\":" + session.conversation().getId()
                                + ",\"title\":\"" + session.conversation().getTitle().replace("\"", "'") + "\"}"));

                emitter.send(SseEmitter.event().name("start").data(""));
                heartbeatRunning.set(true);

                // 心跳保活（15s），防代理超时
                var heartbeatFuture = agentService.heartbeatExecutor().scheduleAtFixedRate(() -> {
                    if (!heartbeatRunning.get()) return;
                    try {
                        emitter.send(SseEmitter.event().comment("ping"));
                    } catch (IOException e) {
                        heartbeatRunning.set(false);
                    }
                }, 15, 15, TimeUnit.SECONDS);

                disposableHolder[0] = agentService.runStream(
                        session,
                        token -> {
                            try {
                                emitter.send(SseEmitter.event().name("token").data(token));
                            } catch (IOException e) {
                                heartbeatRunning.set(false);
                                heartbeatFuture.cancel(false);
                                emitter.completeWithError(e);
                            }
                        },
                        () -> {
                            heartbeatRunning.set(false);
                            heartbeatFuture.cancel(false);
                            try {
                                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                            } catch (IOException ignored) {
                            }
                            emitter.complete();
                        },
                        errorMsg -> {
                            heartbeatRunning.set(false);
                            heartbeatFuture.cancel(false);
                            try {
                                emitter.send(SseEmitter.event().name("error").data(errorMsg));
                            } catch (IOException ignored) {
                            }
                            emitter.complete();
                        });
            } catch (Exception e) {
                log.warn("智能体 SSE 处理异常：{}", e.getMessage());
                heartbeatRunning.set(false);
                try {
                    emitter.send(SseEmitter.event().name("error").data("服务异常，请重试"));
                } catch (IOException ignored) {
                }
                emitter.complete();
            }
        });

        return emitter;
    }

    /** 会话列表 */
    @Operation(summary = "会话列表")
    @GetMapping("/conversations")
    public Result<List<Map<String, Object>>> conversations() {
        String userId = currentUserId();
        List<Map<String, Object>> items = agentService.listConversations(userId).stream()
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.getId());
                    m.put("title", c.getTitle());
                    m.put("updatedAt", c.getUpdatedAt());
                    return m;
                }).toList();
        return Result.success(items);
    }

    /** 会话历史消息 */
    @Operation(summary = "会话历史消息")
    @GetMapping("/conversations/{id}/messages")
    public Result<List<Map<String, Object>>> messages(@PathVariable Long id) {
        String userId = currentUserId();
        List<Map<String, Object>> items = agentService.listMessages(userId, id).stream()
                .map(m -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("role", m.getRole());
                    r.put("content", m.getContent());
                    r.put("createdAt", m.getCreatedAt());
                    return r;
                }).toList();
        return Result.success(items);
    }

    /** 删除会话 */
    @Operation(summary = "删除会话")
    @DeleteMapping("/conversations/{id}")
    public Result<Void> deleteConversation(@PathVariable Long id) {
        agentService.deleteConversation(currentUserId(), id);
        return Result.success(null);
    }
}
