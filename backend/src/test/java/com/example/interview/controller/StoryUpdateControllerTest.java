package com.example.interview.controller;

import com.example.interview.common.ResourceNotFoundException;
import com.example.interview.entity.StoryBankEntity;
import com.example.interview.interceptor.RateLimitInterceptor;
import com.example.interview.security.JwtUtil;
import com.example.interview.service.career.StoryBankService;
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
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code PUT /api/story-bank/{id}} 的 MockMvc 测试（G4，2026-10-18）。
 *
 * <p>只测契约：路径绑定、userId 透传、404 映射。部分更新语义在
 * {@link com.example.interview.service.career.StoryUpdateServiceTest} 里测。
 */
@WebMvcTest(controllers = StoryBankController.class,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration")
@DisplayName("PUT /api/story-bank/{id} 更新故事")
class StoryUpdateControllerTest {

    private static final String USER_ID = "1";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockBean private StoryBankService storyBankService;
    @MockBean private RateLimitInterceptor rateLimitInterceptor;
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

    @Test
    @DisplayName("更新成功 → 200，返回更新后的故事")
    void update_ok_returns200() throws Exception {
        StoryBankEntity patch = new StoryBankEntity();
        patch.setAction("新动作");
        String body = objectMapper.writeValueAsString(patch);

        when(storyBankService.update(eq(USER_ID), eq(7L), any(StoryBankEntity.class)))
                .thenReturn(StoryBankEntity.builder().id(7L).userId(USER_ID)
                        .title("重构订单模块").action("新动作").build());

        mockMvc.perform(put("/api/story-bank/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.id").value(7))
                .andExpect(jsonPath("$.data.action").value("新动作"));
    }

    @Test
    @DisplayName("故事不存在/非本人 → HTTP 404（不是 503）")
    void update_notFound_returns404() throws Exception {
        when(storyBankService.update(eq(USER_ID), eq(999L), any(StoryBankEntity.class)))
                .thenThrow(new ResourceNotFoundException("故事不存在或无权操作"));

        mockMvc.perform(put("/api/story-bank/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }
}
