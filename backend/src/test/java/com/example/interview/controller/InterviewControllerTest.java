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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link InterviewController} 非 SSE 端点 MockMvc 集成测试
 *
 * <p>覆盖 /api/interview/questions、/api/interview/evaluate 两个端点。
 * SSE 流式端点 /ask/stream 涉及异步虚拟线程与 SseEmitter，不在本类覆盖范围。
 *
 * <p>关键点：
 * <ul>
 *   <li>@MockBean 隔离 InterviewService 与 ChatClient，避免真实 AI 调用</li>
 *   <li>SecurityContext 手动注入认证主体，匹配 Controller 内 {@code currentUserId()}</li>
 *   <li>@MockBean JwtUtil：@WebMvcTest 默认拾取 OncePerRequestFilter 子类（JwtAuthFilter），
 *       但不拾取普通 @Component（JwtUtil），需显式 mock 避免依赖注入失败</li>
 *   <li>@MockBean RateLimitInterceptor：默认每 IP 10 次/分钟，12 个用例会触发限流；
 *       mock preHandle 返回 true 绕过限流</li>
 *   <li>Controller 入参校验使用 null 判断（非 trim().isEmpty()），
 *       故"字段缺失"测试用不传字段而非空字符串触发</li>
 * </ul>
 */
@WebMvcTest(controllers = InterviewController.class,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration")
class InterviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private InterviewService interviewService;

    @MockBean
    private JwtUtil jwtUtil;

    /** ChatClient 被 Controller @Autowired，需提供 mock 避免启动失败（SSE 端点未测试，但 Bean 必须存在） */
    @MockBean
    private org.springframework.ai.chat.client.ChatClient chatClient;

    /** 绕过限流：12 个用例会超过 10 次/分钟的默认阈值 */
    @MockBean
    private RateLimitInterceptor rateLimitInterceptor;

    private static final String USER_ID = "1";

    @BeforeEach
    void setUp() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID, null, List.of()));
        // 限流拦截器放行所有请求
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("POST /api/interview/questions 生成面试题")
    class GenerateQuestions {

        @Test
        @DisplayName("简历缺失返回 400")
        void questions_missingResume_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "jobDescription", "Java 后端", "count", 5));

            mockMvc.perform(post("/api/interview/questions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("简历和岗位描述不能为空"));
        }

        @Test
        @DisplayName("岗位描述缺失返回 400")
        void questions_missingJobDescription_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "resumeText", "我的简历", "count", 5));

            mockMvc.perform(post("/api/interview/questions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("简历和岗位描述不能为空"));
        }

        @Test
        @DisplayName("简历和岗位描述均缺失返回 400")
        void questions_missingBothFields_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of("count", 5));

            mockMvc.perform(post("/api/interview/questions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("简历和岗位描述不能为空"));
        }

        @Test
        @DisplayName("合法入参返回 200 + 面试题结果")
        void questions_validInput_returns200() throws Exception {
            when(interviewService.generateQuestions(USER_ID, "我的简历", "Java 后端", 5))
                    .thenReturn("{\"questions\":[]}");

            String body = objectMapper.writeValueAsString(Map.of(
                    "resumeText", "我的简历", "jobDescription", "Java 后端", "count", 5));

            mockMvc.perform(post("/api/interview/questions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").exists());
        }

        @Test
        @DisplayName("count 为负数时夹紧为 1 仍返回 200")
        void questions_negativeCount_clampedTo1() throws Exception {
            when(interviewService.generateQuestions(USER_ID, "我的简历", "Java 后端", 1))
                    .thenReturn("[]");

            String body = objectMapper.writeValueAsString(Map.of(
                    "resumeText", "我的简历", "jobDescription", "Java 后端", "count", -3));

            mockMvc.perform(post("/api/interview/questions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("count 超过 20 时夹紧为 20 仍返回 200")
        void questions_countOver20_clampedTo20() throws Exception {
            when(interviewService.generateQuestions(USER_ID, "我的简历", "Java 后端", 20))
                    .thenReturn("[]");

            String body = objectMapper.writeValueAsString(Map.of(
                    "resumeText", "我的简历", "jobDescription", "Java 后端", "count", 100));

            mockMvc.perform(post("/api/interview/questions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("count 缺失时默认 5 仍返回 200")
        void questions_missingCount_defaultsTo5() throws Exception {
            when(interviewService.generateQuestions(USER_ID, "我的简历", "Java 后端", 5))
                    .thenReturn("[]");

            String body = objectMapper.writeValueAsString(Map.of(
                    "resumeText", "我的简历", "jobDescription", "Java 后端"));

            mockMvc.perform(post("/api/interview/questions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }
    }

    @Nested
    @DisplayName("POST /api/interview/evaluate 评估回答")
    class EvaluateAnswer {

        @Test
        @DisplayName("问题缺失返回 400")
        void evaluate_missingQuestion_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of("userAnswer", "我的回答"));

            mockMvc.perform(post("/api/interview/evaluate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("问题和回答不能为空"));
        }

        @Test
        @DisplayName("回答缺失返回 400")
        void evaluate_missingUserAnswer_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of("question", "什么是多态？"));

            mockMvc.perform(post("/api/interview/evaluate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("问题和回答不能为空"));
        }

        @Test
        @DisplayName("问题和回答均缺失返回 400")
        void evaluate_missingBothFields_returns400() throws Exception {
            String body = "{}";

            mockMvc.perform(post("/api/interview/evaluate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("问题和回答不能为空"));
        }

        @Test
        @DisplayName("合法入参（含参考答案）返回 200 + 评估结果")
        void evaluate_validInputWithReference_returns200() throws Exception {
            when(interviewService.evaluateAnswer("什么是多态？", "多态是...", "参考答案"))
                    .thenReturn("{\"score\":85}");

            String body = objectMapper.writeValueAsString(Map.of(
                    "question", "什么是多态？",
                    "userAnswer", "多态是...",
                    "referenceAnswer", "参考答案"));

            mockMvc.perform(post("/api/interview/evaluate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").exists());
        }

        @Test
        @DisplayName("合法入参（无参考答案）返回 200 + 评估结果")
        void evaluate_validInputWithoutReference_returns200() throws Exception {
            when(interviewService.evaluateAnswer("什么是多态？", "多态是...", ""))
                    .thenReturn("{\"score\":80}");

            String body = objectMapper.writeValueAsString(Map.of(
                    "question", "什么是多态？", "userAnswer", "多态是..."));

            mockMvc.perform(post("/api/interview/evaluate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").exists());
        }
    }
}
