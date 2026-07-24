package com.example.interview.controller;

import com.example.interview.entity.InterviewQuestionEntity;
import com.example.interview.entity.InterviewSessionEntity;
import com.example.interview.interceptor.RateLimitInterceptor;
import com.example.interview.security.JwtUtil;
import com.example.interview.service.InterviewSessionService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link InterviewSessionController} MockMvc 集成测试
 *
 * <p>覆盖 7 个端点：create/list/get/finish/questions(GET)/questions(POST)/answer。
 * 重点验证 IDOR（越权）防御：非本人会话返回 403。
 *
 * <p>关键点：
 * <ul>
 *   <li>@MockBean 隔离 InterviewSessionService</li>
 *   <li>IDOR 测试：mock getBySessionId 返回 userId 不匹配的会话，验证 403</li>
 *   <li>@MockBean RateLimitInterceptor 绕过限流</li>
 * </ul>
 */
@WebMvcTest(controllers = InterviewSessionController.class,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration")
class InterviewSessionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private InterviewSessionService sessionService;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private RateLimitInterceptor rateLimitInterceptor;

    private static final String USER_ID = "1";
    private static final String OTHER_USER_ID = "999";

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

    @Nested
    @DisplayName("POST /api/session/create 创建会话")
    class CreateSession {

        @Test
        @DisplayName("jobDescription 为空返回 400")
        void create_emptyJobDescription_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of("jobDescription", ""));

            mockMvc.perform(post("/api/session/create")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("jobDescription 不能为空"));
        }

        @Test
        @DisplayName("jobDescription 缺失返回 400")
        void create_missingJobDescription_returns400() throws Exception {
            String body = "{}";

            mockMvc.perform(post("/api/session/create")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("jobDescription 不能为空"));
        }

        @Test
        @DisplayName("合法入参返回 200 + 会话实体")
        void create_validInput_returns200() throws Exception {
            InterviewSessionEntity session = InterviewSessionEntity.builder()
                    .id(1L).sessionId("s1").userId(USER_ID)
                    .jobDescription("Java 后端").status("ONGOING").build();
            when(sessionService.createSession(USER_ID, "Java 后端", null))
                    .thenReturn(session);

            String body = objectMapper.writeValueAsString(Map.of("jobDescription", "Java 后端"));

            mockMvc.perform(post("/api/session/create")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.sessionId").value("s1"));
        }

        @Test
        @DisplayName("含 resumeId 返回 200")
        void create_withResumeId_returns200() throws Exception {
            InterviewSessionEntity session = InterviewSessionEntity.builder()
                    .id(1L).sessionId("s1").userId(USER_ID).resumeId(5L)
                    .jobDescription("Java 后端").status("ONGOING").build();
            when(sessionService.createSession(USER_ID, "Java 后端", 5L))
                    .thenReturn(session);

            String body = objectMapper.writeValueAsString(Map.of(
                    "jobDescription", "Java 后端", "resumeId", 5));

            mockMvc.perform(post("/api/session/create")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.resumeId").value(5));
        }
    }

    @Nested
    @DisplayName("GET /api/session/list 会话列表")
    class ListSessions {

        @Test
        @DisplayName("返回 200 + 会话列表")
        void list_returns200() throws Exception {
            InterviewSessionEntity s = InterviewSessionEntity.builder()
                    .id(1L).sessionId("s1").userId(USER_ID)
                    .jobDescription("Java 后端").status("FINISHED").build();
            when(sessionService.listByUser(USER_ID))
                    .thenReturn(List.of(s));

            mockMvc.perform(get("/api/session/list"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data[0].sessionId").value("s1"));
        }

        @Test
        @DisplayName("空列表返回 200 + 空数组")
        void list_empty_returns200() throws Exception {
            when(sessionService.listByUser(USER_ID))
                    .thenReturn(List.of());

            mockMvc.perform(get("/api/session/list"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }
    }

    @Nested
    @DisplayName("GET /api/session/{sessionId} 会话详情（IDOR 防御）")
    class GetSession {

        @Test
        @DisplayName("本人会话返回 200")
        void get_ownSession_returns200() throws Exception {
            InterviewSessionEntity s = InterviewSessionEntity.builder()
                    .id(1L).sessionId("s1").userId(USER_ID)
                    .jobDescription("Java 后端").build();
            when(sessionService.getBySessionId("s1"))
                    .thenReturn(s);

            mockMvc.perform(get("/api/session/s1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.sessionId").value("s1"));
        }

        @Test
        @DisplayName("他人会话返回 403（IDOR 防御）")
        void get_otherUserSession_returns403() throws Exception {
            InterviewSessionEntity s = InterviewSessionEntity.builder()
                    .id(1L).sessionId("s1").userId(OTHER_USER_ID)
                    .jobDescription("Java 后端").build();
            when(sessionService.getBySessionId("s1"))
                    .thenReturn(s);

            mockMvc.perform(get("/api/session/s1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403))
                    .andExpect(jsonPath("$.message").value("无权访问该会话"));
        }
    }

    @Nested
    @DisplayName("PUT /api/session/{sessionId}/finish 结束会话（IDOR 防御）")
    class FinishSession {

        @Test
        @DisplayName("本人会话返回 200")
        void finish_ownSession_returns200() throws Exception {
            InterviewSessionEntity s = InterviewSessionEntity.builder()
                    .id(1L).sessionId("s1").userId(USER_ID)
                    .jobDescription("Java 后端").status("ONGOING").build();
            InterviewSessionEntity finished = InterviewSessionEntity.builder()
                    .id(1L).sessionId("s1").userId(USER_ID)
                    .jobDescription("Java 后端").status("FINISHED").build();
            when(sessionService.getBySessionId("s1")).thenReturn(s);
            when(sessionService.finishSession("s1")).thenReturn(finished);

            mockMvc.perform(put("/api/session/s1/finish"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.status").value("FINISHED"));
        }

        @Test
        @DisplayName("他人会话返回 403（IDOR 防御）")
        void finish_otherUserSession_returns403() throws Exception {
            InterviewSessionEntity s = InterviewSessionEntity.builder()
                    .id(1L).sessionId("s1").userId(OTHER_USER_ID)
                    .jobDescription("Java 后端").build();
            when(sessionService.getBySessionId("s1")).thenReturn(s);

            mockMvc.perform(put("/api/session/s1/finish"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403))
                    .andExpect(jsonPath("$.message").value("无权操作该会话"));
        }
    }

    @Nested
    @DisplayName("GET /api/session/{sessionId}/questions 会话题目（IDOR 防御）")
    class ListQuestions {

        @Test
        @DisplayName("本人会话返回 200 + 题目列表")
        void listQuestions_ownSession_returns200() throws Exception {
            InterviewSessionEntity s = InterviewSessionEntity.builder()
                    .id(1L).sessionId("s1").userId(USER_ID).build();
            InterviewQuestionEntity q = InterviewQuestionEntity.builder()
                    .id(1L).sessionId("s1").question("什么是多态？").build();
            when(sessionService.getBySessionId("s1")).thenReturn(s);
            when(sessionService.listQuestions("s1")).thenReturn(List.of(q));

            mockMvc.perform(get("/api/session/s1/questions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data[0].question").value("什么是多态？"));
        }

        @Test
        @DisplayName("他人会话返回 403（IDOR 防御）")
        void listQuestions_otherUserSession_returns403() throws Exception {
            InterviewSessionEntity s = InterviewSessionEntity.builder()
                    .id(1L).sessionId("s1").userId(OTHER_USER_ID).build();
            when(sessionService.getBySessionId("s1")).thenReturn(s);

            mockMvc.perform(get("/api/session/s1/questions"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));
        }
    }

    @Nested
    @DisplayName("POST /api/session/{sessionId}/questions 批量保存题目（IDOR 防御）")
    class SaveQuestions {

        @Test
        @DisplayName("题目列表为空返回 400")
        void saveQuestions_emptyList_returns400() throws Exception {
            InterviewSessionEntity s = InterviewSessionEntity.builder()
                    .id(1L).sessionId("s1").userId(USER_ID).build();
            when(sessionService.getBySessionId("s1")).thenReturn(s);

            String body = "[]";

            mockMvc.perform(post("/api/session/s1/questions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("题目列表不能为空"));
        }

        @Test
        @DisplayName("题目内容全为空返回 400")
        void saveQuestions_allBlank_returns400() throws Exception {
            InterviewSessionEntity s = InterviewSessionEntity.builder()
                    .id(1L).sessionId("s1").userId(USER_ID).build();
            when(sessionService.getBySessionId("s1")).thenReturn(s);

            String body = objectMapper.writeValueAsString(List.of(
                    Map.of("question", ""),
                    Map.of("question", "  ")));

            mockMvc.perform(post("/api/session/s1/questions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("题目内容不能为空"));
        }

        @Test
        @DisplayName("本人会话合法入参返回 200")
        void saveQuestions_ownSession_returns200() throws Exception {
            InterviewSessionEntity s = InterviewSessionEntity.builder()
                    .id(1L).sessionId("s1").userId(USER_ID).build();
            when(sessionService.getBySessionId("s1")).thenReturn(s);
            InterviewQuestionEntity saved = InterviewQuestionEntity.builder()
                    .id(1L).sessionId("s1").question("什么是多态？").build();
            when(sessionService.saveQuestions(any(String.class), any()))
                    .thenReturn(List.of(saved));

            String body = objectMapper.writeValueAsString(List.of(
                    Map.of("question", "什么是多态？", "category", "Java 基础")));

            mockMvc.perform(post("/api/session/s1/questions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data[0].question").value("什么是多态？"));
        }

        @Test
        @DisplayName("他人会话返回 403（IDOR 防御）")
        void saveQuestions_otherUserSession_returns403() throws Exception {
            InterviewSessionEntity s = InterviewSessionEntity.builder()
                    .id(1L).sessionId("s1").userId(OTHER_USER_ID).build();
            when(sessionService.getBySessionId("s1")).thenReturn(s);

            String body = objectMapper.writeValueAsString(List.of(
                    Map.of("question", "什么是多态？")));

            mockMvc.perform(post("/api/session/s1/questions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(403));
        }
    }

    @Nested
    @DisplayName("POST /api/session/answer 保存回答")
    class SaveAnswer {

        @Test
        @DisplayName("questionId 缺失返回 400")
        void saveAnswer_missingQuestionId_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of("userAnswer", "我的回答"));

            mockMvc.perform(post("/api/session/answer")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("questionId 不能为空"));
        }

        @Test
        @DisplayName("userAnswer 为空返回 400")
        void saveAnswer_emptyUserAnswer_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "questionId", 1, "userAnswer", ""));

            mockMvc.perform(post("/api/session/answer")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("userAnswer 不能为空"));
        }

        @Test
        @DisplayName("合法入参返回 200 + 已保存题目")
        void saveAnswer_validInput_returns200() throws Exception {
            InterviewQuestionEntity saved = InterviewQuestionEntity.builder()
                    .id(1L).sessionId("s1").question("什么是多态？")
                    .userAnswer("多态是...").evaluationScore(85).build();
            when(sessionService.saveAnswer(1L, "多态是...", 85, USER_ID))
                    .thenReturn(saved);

            String body = objectMapper.writeValueAsString(Map.of(
                    "questionId", 1, "userAnswer", "多态是...", "evaluationScore", 85));

            mockMvc.perform(post("/api/session/answer")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.evaluationScore").value(85));
        }

        @Test
        @DisplayName("无评分字段合法入参返回 200")
        void saveAnswer_withoutScore_returns200() throws Exception {
            InterviewQuestionEntity saved = InterviewQuestionEntity.builder()
                    .id(1L).sessionId("s1").question("什么是多态？")
                    .userAnswer("多态是...").build();
            when(sessionService.saveAnswer(1L, "多态是...", null, USER_ID))
                    .thenReturn(saved);

            String body = objectMapper.writeValueAsString(Map.of(
                    "questionId", 1, "userAnswer", "多态是..."));

            mockMvc.perform(post("/api/session/answer")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.userAnswer").value("多态是..."));
        }
    }
}
