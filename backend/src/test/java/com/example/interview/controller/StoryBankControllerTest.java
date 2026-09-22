package com.example.interview.controller;

import com.example.interview.interceptor.RateLimitInterceptor;
import com.example.interview.security.JwtUtil;
import com.example.interview.service.career.StoryBankService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link StoryBankController} MockMvc 测试（v1.36.0 面试故事库）
 *
 * <p>验证：参数校验、归属校验（防 IDOR 返回 404）、JSON 解析兜底不 500、删除放行。
 */
@WebMvcTest(controllers = StoryBankController.class,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration")
class StoryBankControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private StoryBankService storyBankService;
    @MockBean
    private JwtUtil jwtUtil;
    @MockBean
    private RateLimitInterceptor rateLimitInterceptor;

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
    @DisplayName("GET /api/story-bank：返回故事列表")
    void list_ok() throws Exception {
        loginAs("user-1");
        when(storyBankService.list(eq("user-1"))).thenReturn(java.util.List.of());

        mockMvc.perform(get("/api/story-bank"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("POST /api/story-bank/extract：narrative 为空返回 400")
    void extract_blankNarrative() throws Exception {
        loginAs("user-1");

        mockMvc.perform(post("/api/story-bank/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("POST /api/story-bank/extract：返回解析后的故事候选")
    void extract_ok() throws Exception {
        loginAs("user-1");
        when(storyBankService.extract(eq("user-1"), anyString(), anyString()))
                .thenReturn("{\"stories\":[{\"title\":\"二手交易平台性能优化\",\"capabilities\":[\"性能优化\"]}]}");

        mockMvc.perform(post("/api/story-bank/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"narrative\":\"我做过二手交易平台\",\"targetTrack\":\"Java 后端\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.stories[0].title").value("二手交易平台性能优化"));
    }

    @Test
    @DisplayName("POST /api/story-bank/{id}/check：非本人故事返回 404（防 IDOR）")
    void check_notOwner_returns404() throws Exception {
        loginAs("user-1");
        when(storyBankService.findOwned(eq("user-1"), eq(9L))).thenReturn(null);

        mockMvc.perform(post("/api/story-bank/9/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"我的回答\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("POST /api/story-bank/{id}/check：answer 为空返回 400")
    void check_blankAnswer() throws Exception {
        loginAs("user-1");

        mockMvc.perform(post("/api/story-bank/1/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("POST /api/story-bank/{id}/followup：本人故事正常返回追问链")
    void followup_ok() throws Exception {
        loginAs("user-1");
        var story = com.example.interview.entity.StoryBankEntity.builder()
                .id(1L).userId("user-1").title("故事").build();
        when(storyBankService.findOwned(eq("user-1"), eq(1L))).thenReturn(story);
        when(storyBankService.followUp(any(), anyString()))
                .thenReturn("{\"followups\":[\"追问1\",\"追问2\",\"追问3\"]}");

        mockMvc.perform(post("/api/story-bank/1/followup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answer\":\"我的口述回答\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.followups.length()").value(3));
    }

    @Test
    @DisplayName("DELETE /api/story-bank/{id}：删除成功")
    void delete_ok() throws Exception {
        loginAs("user-1");

        mockMvc.perform(delete("/api/story-bank/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
