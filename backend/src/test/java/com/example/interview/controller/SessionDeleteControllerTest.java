package com.example.interview.controller;

import com.example.interview.common.ResourceNotFoundException;
import com.example.interview.entity.InterviewSessionEntity;
import com.example.interview.interceptor.RateLimitInterceptor;
import com.example.interview.security.JwtUtil;
import com.example.interview.service.InterviewSessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code DELETE /api/session/{sessionId}} 的 MockMvc 测试（G2-b，2026-10-18）。
 *
 * <p>只测契约：路径、越权分流、状态码。删除语义在
 * {@link com.example.interview.service.SessionDeleteServiceTest} 里测。
 *
 * <p>注意越权那一条的**状态码是 HTTP 200 + 业务码 403**，不是真 403 ——
 * 这是本项目既定的「controller 层 return Result.error(...)」形态；而 service 抛出的
 * AccessDeniedException 走 @ExceptionHandler 才是真 403。两种形态并存，
 * 断言必须按分支写，否则会把正确实现误判成 bug。
 */
@WebMvcTest(controllers = InterviewSessionController.class,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration")
@DisplayName("DELETE /api/session/{sessionId} 删除会话")
class SessionDeleteControllerTest {

    private static final String USER_ID = "1";

    @Autowired
    private MockMvc mockMvc;

    @MockBean private InterviewSessionService sessionService;
    @MockBean private RateLimitInterceptor rateLimitInterceptor;
    /** JwtAuthFilter 在切片内会被装配，它依赖 JwtUtil；缺了上下文起不来 */
    @MockBean private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID, null, List.of()));
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private InterviewSessionEntity owned(String sessionId, String userId) {
        return InterviewSessionEntity.builder().sessionId(sessionId).userId(userId).build();
    }

    @Test
    @DisplayName("本人会话 → 200，data.deletedQuestions 透出题目数")
    void delete_ownSession_returns200() throws Exception {
        when(sessionService.getBySessionId("s1")).thenReturn(owned("s1", USER_ID));
        when(sessionService.deleteSession(eq("s1"), eq(USER_ID))).thenReturn(3L);

        mockMvc.perform(delete("/api/session/s1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.deletedQuestions").value(3));

        verify(sessionService).deleteSession("s1", USER_ID);
    }

    @Test
    @DisplayName("他人会话 → HTTP 200 + code 403，且**绝不调用删除**")
    void delete_otherUsersSession_returns403CodeAndNoDelete() throws Exception {
        when(sessionService.getBySessionId("s2")).thenReturn(owned("s2", "someone-else"));

        mockMvc.perform(delete("/api/session/s2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("无权删除该会话"));

        verify(sessionService, never()).deleteSession(any(), any());
    }

    @Test
    @DisplayName("会话不存在 → HTTP 404")
    void delete_notFound_returns404() throws Exception {
        when(sessionService.getBySessionId("nope"))
                .thenThrow(new ResourceNotFoundException("会话不存在：nope"));

        mockMvc.perform(delete("/api/session/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }
}
