package com.example.interview.controller;

import com.example.interview.interceptor.RateLimitInterceptor;
import com.example.interview.security.JwtUtil;
import com.example.interview.service.InterviewService;
import com.example.interview.util.PerUserRateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code InterviewController} 自身按用户限流的「429 口径」测试（2026-10-21 收口）。
 *
 * <h2>锁住什么</h2>
 *
 * <p>限流分支已从 {@code return Result.error(429, ...)}（HTTP 200）改为
 * {@code throw new RateLimitExceededException(...)}，由
 * {@code GlobalExceptionHandler#handleRateLimitExceeded} 统一给出 <b>真 HTTP 429</b>。
 * 本用例断言状态码 429 + body {@code code=429} + 文案原样保留，
 * 并断言超限请求<b>不会触达 AI 服务</b>（否则「限流」只是幻觉）。
 *
 * <h2>为什么要临时替换 {@code aiLimiter} 并原样恢复</h2>
 *
 * <p>{@code @WebMvcTest} 的上下文（以及其中的控制器实例）按「注解 + mock 组合」缓存，
 * 本类与 {@link InterviewControllerTest} 的注解与 mock 组合一致 → <b>共用同一个上下文与
 * 同一个控制器 Bean</b>。直接打满 30 次/分钟的配额会让那个类里十几个用例全变成 429；
 * 而反射换成一个配额 1 的新限流器若不恢复，同样会污染它们。
 *
 * <p>因此：进入时先<b>保存原限流器引用</b>（不触碰它，它的内部计数保持原样），
 * 换上一个配额 1 的临时实例；退出时把原引用<b>原样放回</b>。
 * 用「恢复原引用」而不是「新建一个 30 次的」，是为了不与控制器里的配额常量重复定义 ——
 * 将来配额改动不会让这里变成一个隐性错误。
 */
@WebMvcTest(controllers = InterviewController.class,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration")
@DisplayName("InterviewController AI 端点限流 429 口径")
class InterviewControllerRateLimitTest {

    private static final String USER_ID = "1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InterviewController interviewController;

    @MockBean
    private InterviewService interviewService;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private org.springframework.ai.chat.client.ChatClient chatClient;

    @MockBean
    private com.example.interview.service.SupabaseStorageService supabaseStorageService;

    /** 绕过 IP 级限流拦截器（默认 10 次/分钟），否则会先被它拦下 */
    @MockBean
    private RateLimitInterceptor rateLimitInterceptor;

    /** 原始限流器引用（不触碰其内部计数，退出时原样放回） */
    private Object originalLimiter;

    @BeforeEach
    void setUp() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID, null, List.of()));
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);

        originalLimiter = ReflectionTestUtils.getField(interviewController, "aiLimiter");
        ReflectionTestUtils.setField(interviewController, "aiLimiter",
                new PerUserRateLimiter(1, 60_000L));
    }

    @AfterEach
    void tearDown() {
        // 必须无条件恢复：上下文与控制器 Bean 与 InterviewControllerTest 共用
        ReflectionTestUtils.setField(interviewController, "aiLimiter", originalLimiter);
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("配额耗尽后返回真 HTTP 429（不再是 HTTP 200 + body code=429）")
    void questions_beyondQuota_returnsRealHttp429() throws Exception {
        String body = objectMapper.writeValueAsString(questionsBody());

        // 配额 1：第 1 次放行
        mockMvc.perform(post("/api/interview/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 第 2 次：真 HTTP 429 + body code=429 + 文案原样
        mockMvc.perform(post("/api/interview/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.message").value("操作过于频繁，请稍后再试"));

        // 超限请求绝不能进入业务链路：恰好 1 次 AI 生成调用（第 2 次没有多烧一次）
        verify(interviewService, org.mockito.Mockito.times(1)).generateQuestions(
                any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    private static Map<String, Object> questionsBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("resumeText", "Java 后端 5 年经验");
        body.put("jobDescription", "Java 高级工程师");
        body.put("count", 3);
        return body;
    }
}
