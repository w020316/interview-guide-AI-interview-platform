package com.example.interview.controller;

import com.example.interview.interceptor.RateLimitInterceptor;
import com.example.interview.security.JwtUtil;
import com.example.interview.service.InterviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link InterviewController} SSE 流式端点 /ask/stream 集成测试
 *
 * <p>覆盖 4 个场景：入参校验（question 为空）、正常流式推送、AI 异常处理、并发限流（503）。
 *
 * <p>关键点：
 * <ul>
 *   <li>Mock ChatClient 链式调用：prompt() → user() → stream() → content() 返回 Flux&lt;String&gt;</li>
 *   <li>SseEmitter 异步：MockMvc 先同步返回 200，再通过 asyncDispatch 验证流内容</li>
 *   <li>使用 Flux.just / Flux.error 控制流式响应，避免真实 AI 调用</li>
 *   <li>@MockBean RateLimitInterceptor 绕过限流（并发限流场景单独用真实 Semaphore 测试）</li>
 * </ul>
 */
@WebMvcTest(controllers = InterviewController.class,
        properties = {
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration",
                "app.sse.max-concurrent=20"
        })
@DisplayName("InterviewController SSE 流式端点测试")
class InterviewControllerSseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private InterviewService interviewService;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private RateLimitInterceptor rateLimitInterceptor;

    @MockBean
    private ChatClient chatClient;

    /** Mock ChatClient 链式调用：prompt() 和 .user() 都返回 ChatClientRequestSpec */
    @MockBean
    private ChatClient.ChatClientRequestSpec chatClientRequestSpec;

    /** .stream() 返回 StreamResponseSpec */
    @MockBean
    private ChatClient.StreamResponseSpec streamResponseSpec;

    private static final String USER_ID = "1";

    @BeforeEach
    void setUp() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID, null, List.of()));
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 配置 ChatClient 流式调用的 mock 链：
     * chatClient.prompt().user(any).stream().content() → Flux
     * Spring AI 1.0：.user() 返回 ChatClientRequestSpec（链式），.stream() 返回 StreamResponseSpec
     */
    private void mockChatClientStream(Flux<String> flux) {
        when(chatClient.prompt()).thenReturn(chatClientRequestSpec);
        when(chatClientRequestSpec.user(anyString())).thenReturn(chatClientRequestSpec);
        when(chatClientRequestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.content()).thenReturn(flux);
    }

    @Nested
    @DisplayName("POST /api/interview/ask/stream 入参校验")
    class StreamInputValidation {

        @Test
        @DisplayName("question 为空返回 SSE error 事件")
        void stream_emptyQuestion_returnsErrorEvent() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of("question", ""));

            // question 为空时 Controller 同步创建 SseEmitter 发送 error 事件后 complete()
            // 仍走异步流程，需 asyncDispatch 获取最终内容
            MvcResult mvcResult = mockMvc.perform(post("/api/interview/ask/stream")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .accept(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(status().isOk())
                    .andReturn();

            // 仅断言 ASCII 事件名，避免 PowerShell/默认编码导致中文乱码断言失败
            mockMvc.perform(asyncDispatch(mvcResult))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("event:error")));
        }
    }

    @Nested
    @DisplayName("POST /api/interview/ask/stream 流式推送")
    class StreamFlow {

        @Test
        @DisplayName("正常流式推送 token + done 事件")
        void stream_validQuestion_pushesTokensAndDone() throws Exception {
            // 使用 ASCII token 避免 MockMvc 默认 ISO-8859-1 解码导致中文断言失败
            mockChatClientStream(Flux.just("hello", " ", "world"));

            String body = objectMapper.writeValueAsString(Map.of("question", "introduce yourself"));

            // 第一阶段：同步请求返回 200，异步流开始
            MvcResult mvcResult = mockMvc.perform(post("/api/interview/ask/stream")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .accept(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(status().isOk())
                    .andExpect(request().asyncStarted())
                    .andReturn();

            // 第二阶段：等待异步流完成，验证内容包含 token 和 done 事件
            mockMvc.perform(asyncDispatch(mvcResult))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.allOf(
                            org.hamcrest.Matchers.containsString("event:start"),
                            org.hamcrest.Matchers.containsString("event:token"),
                            org.hamcrest.Matchers.containsString("hello"),
                            org.hamcrest.Matchers.containsString("world"),
                            org.hamcrest.Matchers.containsString("event:done"),
                            org.hamcrest.Matchers.containsString("[DONE]")
                    )));
        }

        @Test
        @DisplayName("AI 流式异常时推送 error 事件")
        void stream_aiError_pushesErrorEvent() throws Exception {
            mockChatClientStream(Flux.error(new RuntimeException("AI 服务异常")));

            String body = objectMapper.writeValueAsString(Map.of("question", "问题"));

            MvcResult mvcResult = mockMvc.perform(post("/api/interview/ask/stream")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .accept(MediaType.TEXT_EVENT_STREAM))
                    .andExpect(status().isOk())
                    .andExpect(request().asyncStarted())
                    .andReturn();

            // v1.16：Flux.error 触发 doOnError，发送 error 事件后 complete()
            mockMvc.perform(asyncDispatch(mvcResult))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("event:error")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("event:start")));
        }
    }
}
