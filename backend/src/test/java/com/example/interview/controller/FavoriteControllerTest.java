package com.example.interview.controller;

import com.example.interview.entity.FavoriteQuestionEntity;
import com.example.interview.entity.InterviewQuestionEntity;
import com.example.interview.entity.InterviewSessionEntity;
import com.example.interview.interceptor.RateLimitInterceptor;
import com.example.interview.security.JwtUtil;
import com.example.interview.service.FavoriteService;
import com.example.interview.service.InterviewSessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link FavoriteController} MockMvc 集成测试（重点：从收藏题库发起面试 /bank/start）
 *
 * <p>验证：happy path 返回会话 + 题目；空 favoriteIds 返回 400；越权（收藏不属于当前用户）返回 404。
 */
@WebMvcTest(controllers = FavoriteController.class,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration")
class FavoriteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private FavoriteService favoriteService;

    @MockBean
    private InterviewSessionService sessionService;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private RateLimitInterceptor rateLimitInterceptor;

    private FavoriteQuestionEntity fav(Long id, String q, String cat, String diff) {
        return FavoriteQuestionEntity.builder()
                .id(id)
                .userId("user-1")
                .question(q)
                .category(cat)
                .difficulty(diff)
                .referenceAnswer("参考答案")
                .build();
    }

    @BeforeEach
    void setUp() throws Exception {
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(String userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    @Test
    void bankStart_happyPath_returnsSessionAndQuestions() throws Exception {
        loginAs("user-1");
        List<FavoriteQuestionEntity> favs = List.of(fav(1L, "自我介绍", "项目", "EASY"),
                fav(2L, "HashMap 原理", "Java 基础", "MEDIUM"));
        when(favoriteService.listByUser("user-1")).thenReturn(favs);

        InterviewSessionEntity session = InterviewSessionEntity.builder()
                .sessionId("sess-abc").userId("user-1").jobDescription("Java 后端").build();
        when(sessionService.createSession(eq("user-1"), anyString(), isNull())).thenReturn(session);

        InterviewQuestionEntity q1 = InterviewQuestionEntity.builder()
                .id(10L).sessionId("sess-abc").question("自我介绍").category("项目").difficulty("EASY")
                .referenceAnswer("参考答案").build();
        when(sessionService.saveQuestions(eq("sess-abc"), any())).thenReturn(List.of(q1));

        mockMvc.perform(post("/api/favorite/bank/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"favoriteIds\":[1,2],\"jobDescription\":\"Java 后端\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionId").value("sess-abc"))
                .andExpect(jsonPath("$.data.questions[0].question").value("自我介绍"));
    }

    @Test
    void bankStart_emptyIds_returns400() throws Exception {
        loginAs("user-1");
        mockMvc.perform(post("/api/favorite/bank/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"favoriteIds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void bankStart_ownershipMismatch_returns404() throws Exception {
        loginAs("user-1");
        // 当前用户只有 id=99 的收藏，但请求想用 id=1
        when(favoriteService.listByUser("user-1")).thenReturn(List.of(fav(99L, "不属于", "其他", "EASY")));
        mockMvc.perform(post("/api/favorite/bank/start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"favoriteIds\":[1]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }
}