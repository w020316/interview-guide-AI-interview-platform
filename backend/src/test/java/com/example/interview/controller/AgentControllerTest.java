package com.example.interview.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.Disposable;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 智能体控制器测试
 * 重点覆盖：JWT 认证上下文注入、会话列表/历史消息返回、防越权空返回
 *
 * 说明：Controller 直接从 SecurityContextHolder 读取 principal，
 * standalone MockMvc 不自动填充，测试中手动 set/clear。
 */
@ExtendWith(MockitoExtension.class)
class AgentControllerTest {

    @Mock
    private com.example.interview.service.agent.AgentService agentService;

    @InjectMocks
    private AgentController agentController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(agentController).build();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("u1", null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        heartbeatExecutor.shutdownNow();
    }

    @Test
    @DisplayName("会话列表：正常返回用户会话")
    void conversationsOk() throws Exception {
        when(agentService.listConversations(anyString()))
                .thenReturn(List.of(
                        com.example.interview.entity.AgentConversationEntity.builder()
                                .id(1L).userId("u1").title("找岗位").build()));

        mockMvc.perform(get("/api/agent/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].title").value("找岗位"));
    }

    @Test
    @DisplayName("历史消息：他人会话返回空列表（防越权）")
    void messagesOfOthersReturnEmpty() throws Exception {
        when(agentService.listMessages(anyString(), anyLong()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/agent/conversations/999/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("流式对话：空消息返回 error 事件")
    void streamChatEmptyMessage() throws Exception {
        // SseEmitter 为异步派发，需等待 async 结果后再断言
        org.springframework.test.web.servlet.MvcResult mvcResult = mockMvc.perform(post("/api/agent/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
                .andReturn();

        String content = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // 注：MockMvc 默认 ISO-8859-1 编码会损坏中文，仅断言 error 事件名与非空 data
        org.junit.jupiter.api.Assertions.assertTrue(content.contains("event:error"));
        org.junit.jupiter.api.Assertions.assertTrue(content.contains("data:"));
    }

    // ───────────────── 流式对话：并发保护 / 会话失败 / 完整流式 ─────────────────

    private final ScheduledExecutorService heartbeatExecutor = Executors.newScheduledThreadPool(1);

    /** 执行异步请求并返回 SSE 响应全文（UTF-8 解码） */
    private String performStreamAndGetContent(String body) throws Exception {
        org.springframework.test.web.servlet.MvcResult mvcResult = mockMvc.perform(
                        post("/api/agent/chat/stream")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
                .andReturn();
        return mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("流式对话：达到每用户上限时返回限流提示事件且不创建会话")
    void streamChat_userLimit_errorEvent() throws Exception {
        when(agentService.tryAcquire("u1"))
                .thenReturn(com.example.interview.util.SseConcurrencyGuard.Result.USER_LIMIT);

        String content = performStreamAndGetContent("{\"message\":\"你好\"}");

        // 注：standalone MockMvc 下 SSE 中文以 ISO-8859-1 写出会损坏，仅断言事件名与非空 data
        assertThatSseError(content);
        verify(agentService, org.mockito.Mockito.never()).streamChat(anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("流式对话：达到全局上限时返回繁忙提示事件")
    void streamChat_globalLimit_errorEvent() throws Exception {
        when(agentService.tryAcquire("u1"))
                .thenReturn(com.example.interview.util.SseConcurrencyGuard.Result.GLOBAL_LIMIT);

        String content = performStreamAndGetContent("{\"message\":\"你好\"}");

        assertThatSseError(content);
    }

    @Test
    @DisplayName("流式对话：会话解析失败返回错误事件并释放槽位")
    void streamChat_sessionCreationFails_errorEventAndRelease() throws Exception {
        when(agentService.tryAcquire("u1"))
                .thenReturn(com.example.interview.util.SseConcurrencyGuard.Result.ACQUIRED);
        when(agentService.streamChat(eq("u1"), any(), anyString(), any(AtomicBoolean.class)))
                .thenThrow(new RuntimeException("db down"));

        String content = performStreamAndGetContent("{\"message\":\"你好\",\"conversationId\":9}");

        assertThatSseError(content);
        // onCompletion 释放 SSE 槽位
        verify(agentService).release("u1");
    }

    @Test
    @DisplayName("流式对话：完整流式流程 meta/start/token/done/心跳 ping 事件齐备并释放槽位")
    void streamChat_happyPath_fullEvents() throws Exception {
        when(agentService.tryAcquire("u1"))
                .thenReturn(com.example.interview.util.SseConcurrencyGuard.Result.ACQUIRED);
        // 心跳调度器 stub：注册时立即执行一次心跳体，验证 ping 事件（真实调度 15s 后才首跳）
        ScheduledExecutorService heartbeat = org.mockito.Mockito.mock(ScheduledExecutorService.class);
        java.util.concurrent.ScheduledFuture<?> futureMock = org.mockito.Mockito.mock(java.util.concurrent.ScheduledFuture.class);
        doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return futureMock;
        }).when(heartbeat).scheduleAtFixedRate(any(), anyLong(), anyLong(), any());
        when(agentService.heartbeatExecutor()).thenReturn(heartbeat);

        var conversation = com.example.interview.entity.AgentConversationEntity.builder()
                .id(42L).userId("u1").title("求职咨询").build();
        var session = new com.example.interview.service.agent.AgentService.AgentStreamSession(
                conversation, "SYS", List.of(), "你好", new AtomicBoolean(false));
        when(agentService.streamChat(eq("u1"), eq(42L), anyString(), any(AtomicBoolean.class)))
                .thenReturn(session);
        doAnswer(inv -> {
            java.util.function.Consumer<String> onToken = inv.getArgument(1);
            Runnable onComplete = inv.getArgument(2);
            onToken.accept("回答分块一；");
            onToken.accept("回答分块二。");
            onComplete.run();
            return mock(Disposable.class);
        }).when(agentService).runStream(eq(session), any(), any(), any());

        String content = performStreamAndGetContent("{\"message\":\"你好\",\"conversationId\":42}");

        // 中文经 ISO-8859-1 写出会损坏，仅断言事件序列与 ASCII 内容
        assertThat(content)
                .contains("event:meta").contains("\"conversationId\":42")
                .contains("event:start")
                .contains(":ping") // 心跳 comment 事件
                .contains("event:token")
                .contains("event:done").contains("[DONE]");
        verify(heartbeat).scheduleAtFixedRate(any(), anyLong(), anyLong(), any());
        verify(agentService).release("u1");
    }

    @Test
    @DisplayName("流式对话：AI 失败回调走 onError→error 事件并释放槽位")
    void streamChat_errorCallback_errorEvent() throws Exception {
        when(agentService.tryAcquire("u1"))
                .thenReturn(com.example.interview.util.SseConcurrencyGuard.Result.ACQUIRED);
        when(agentService.heartbeatExecutor()).thenReturn(heartbeatExecutor);
        var conversation = com.example.interview.entity.AgentConversationEntity.builder()
                .id(43L).userId("u1").title("t").build();
        var session = new com.example.interview.service.agent.AgentService.AgentStreamSession(
                conversation, "SYS", List.of(), "你好", new AtomicBoolean(false));
        when(agentService.streamChat(eq("u1"), eq(43L), anyString(), any(AtomicBoolean.class)))
                .thenReturn(session);
        doAnswer(inv -> {
            java.util.function.Consumer<String> onError = inv.getArgument(3);
            onError.accept("AI 服务异常，请重试");
            return mock(Disposable.class);
        }).when(agentService).runStream(eq(session), any(), any(), any());

        String content = performStreamAndGetContent("{\"message\":\"你好\",\"conversationId\":43}");

        assertThatSseError(content);
        verify(agentService).release("u1");
    }

    @Test
    @DisplayName("流式对话：完成后取消 Disposable（异步回调场景）")
    void streamChat_onCompletion_disposesDisposable() throws Exception {
        when(agentService.tryAcquire("u1"))
                .thenReturn(com.example.interview.util.SseConcurrencyGuard.Result.ACQUIRED);
        when(agentService.heartbeatExecutor()).thenReturn(heartbeatExecutor);
        var conversation = com.example.interview.entity.AgentConversationEntity.builder()
                .id(44L).userId("u1").title("t").build();
        var session = new com.example.interview.service.agent.AgentService.AgentStreamSession(
                conversation, "SYS", List.of(), "你好", new AtomicBoolean(false));
        when(agentService.streamChat(eq("u1"), eq(44L), anyString(), any(AtomicBoolean.class)))
                .thenReturn(session);
        Disposable disposable = mock(Disposable.class);
        when(disposable.isDisposed()).thenReturn(false);
        // 回调异步执行：runStream 先返回 Disposable（写入 holder），之后才触发 emitter.complete()
        doAnswer(inv -> {
            java.util.function.Consumer<String> onToken = inv.getArgument(1);
            Runnable onComplete = inv.getArgument(2);
            Thread worker = new Thread(() -> {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ignored) {
                }
                onToken.accept("块");
                onComplete.run();
            });
            worker.setDaemon(true);
            worker.start();
            return disposable;
        }).when(agentService).runStream(eq(session), any(), any(), any());

        performStreamAndGetContent("{\"message\":\"你好\",\"conversationId\":44}");

        // onCompletion 应 dispose 尚未结束的 Disposable
        verify(disposable, org.mockito.Mockito.timeout(5000)).dispose();
        verify(agentService).release("u1");
    }

    /** 断言 SSE 响应含 error 事件且 data 非空 */
    private void assertThatSseError(String content) {
        org.junit.jupiter.api.Assertions.assertTrue(content.contains("event:error"), content);
        org.junit.jupiter.api.Assertions.assertTrue(content.matches("(?s).*data:.+"), content);
    }

    // ───────────────── 会话列表 / 历史消息 / 删除会话 ─────────────────

    @Test
    @DisplayName("历史消息：返回角色与内容映射")
    void messages_withContent_returnsMapping() throws Exception {
        var msg = com.example.interview.entity.AgentMessageEntity.builder()
                .id(1L).role("USER").content("帮我找岗位").build();
        when(agentService.listMessages("u1", 5L)).thenReturn(List.of(msg));

        mockMvc.perform(get("/api/agent/conversations/5/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].role").value("USER"))
                .andExpect(jsonPath("$.data[0].content").value("帮我找岗位"));
    }

    @Test
    @DisplayName("删除会话：返回成功业务码")
    void deleteConversation_returns200() throws Exception {
        mockMvc.perform(delete("/api/agent/conversations/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        verify(agentService).deleteConversation("u1", 5L);
    }

    @Test
    @DisplayName("未认证用户访问会话列表抛出未认证状态（全局处理器兜底）")
    void conversations_unauthenticated_throws() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> mockMvc.perform(get("/api/agent/conversations")))
                .hasRootCauseInstanceOf(IllegalStateException.class);
    }
}
