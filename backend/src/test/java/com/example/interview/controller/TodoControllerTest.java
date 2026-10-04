package com.example.interview.controller;

import com.example.interview.interceptor.RateLimitInterceptor;
import com.example.interview.security.JwtUtil;
import com.example.interview.service.TodoService;
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
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link TodoController} MockMvc 测试（第三批 B）。
 */
@WebMvcTest(controllers = TodoController.class,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration")
class TodoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TodoService todoService;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private RateLimitInterceptor rateLimitInterceptor;

    @BeforeEach
    void setUp() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("1", null, List.of()));
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("GET /api/todo/today 返回聚合结构（code=200）")
    void today_returns200() throws Exception {
        when(todoService.today(any(), anyInt())).thenReturn(Map.of(
                "generatedAt", 123L,
                "groups", List.of(Map.of("key", "FOLLOW_UP", "label", "待跟进投递",
                        "count", 2, "available", true, "items", List.of())),
                "partialFailures", List.of()));

        mockMvc.perform(get("/api/todo/today?days=3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.groups[0].key").value("FOLLOW_UP"))
                .andExpect(jsonPath("$.data.groups[0].count").value(2));
    }

    @Test
    @DisplayName("GET /api/todo/today 源不可用时 count 为 null（无数据 ≠ 0）")
    void today_sourceUnavailable_countNull() throws Exception {
        java.util.Map<String, Object> group = new java.util.LinkedHashMap<>();
        group.put("key", "FOLLOW_UP");
        group.put("label", "待跟进投递");
        group.put("count", null);
        group.put("available", false);
        group.put("items", List.of());
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("generatedAt", 123L);
        body.put("groups", List.of(group));
        body.put("partialFailures", List.of(Map.of("group", "FOLLOW_UP", "message", "取不到")));
        when(todoService.today(any(), anyInt())).thenReturn(body);

        mockMvc.perform(get("/api/todo/today"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.groups[0].count").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.groups[0].available").value(false))
                .andExpect(jsonPath("$.data.partialFailures[0].group").value("FOLLOW_UP"));
    }
}
