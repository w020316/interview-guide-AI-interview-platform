package com.example.interview.controller;

import com.example.interview.interceptor.RateLimitInterceptor;
import com.example.interview.security.JwtUtil;
import com.example.interview.service.career.CareerProfileService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link CareerController} MockMvc 测试（v1.35.0 求职 Skill）
 *
 * <p>验证：参数校验（narrative / targetJob 必填）、JSON 解析为对象返回、解析失败时兜底不 500。
 */
@WebMvcTest(controllers = CareerController.class,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration")
class CareerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CareerProfileService careerProfileService;
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
    @DisplayName("POST /api/career/mine：返回解析后的结构化对象")
    void mine_ok() throws Exception {
        loginAs("user-1");
        when(careerProfileService.mine(eq("user-1"), anyString(), anyString())).thenReturn(
                "{\"positioning\":\"懂业务的 Java 后端\",\"assets\":[{\"evidence\":\"把慢查询改成 ES\","
                        + "\"behavior\":\"重构检索\",\"capability\":\"性能优化能力\",\"jobSignals\":[\"Java 后端\"]}],"
                        + "\"strengthTags\":[\"性能优化\"],\"blindSpots\":[],\"nextSteps\":[]}");

        mockMvc.perform(post("/api/career/mine")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"narrative\":\"我做过二手交易平台\",\"targetTrack\":\"Java 后端\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.positioning").value("懂业务的 Java 后端"))
                .andExpect(jsonPath("$.data.assets[0].capability").value("性能优化能力"));
    }

    @Test
    @DisplayName("POST /api/career/mine：narrative 为空返回 400")
    void mine_blankNarrative() throws Exception {
        loginAs("user-1");

        mockMvc.perform(post("/api/career/mine")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("POST /api/career/mine：AI 返回非法 JSON 时兜底为 raw 字段（不 500）")
    void mine_unparsable_fallback() throws Exception {
        loginAs("user-1");
        when(careerProfileService.mine(eq("user-1"), anyString(), any()))
                .thenReturn("这不是 JSON");

        mockMvc.perform(post("/api/career/mine")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"narrative\":\"经历\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.raw").value("这不是 JSON"));
    }

    @Test
    @DisplayName("POST /api/career/plan：返回 whyFit 与 30 天计划")
    void plan_ok() throws Exception {
        loginAs("user-1");
        when(careerProfileService.plan(eq("user-1"), anyString(), anyString(), anyString())).thenReturn(
                "{\"whyFit\":\"技术栈高度吻合\",\"gaps\":[],\"thirtyDayPlan\":[{\"week\":\"第1周\","
                        + "\"focus\":\"补齐分布式\",\"tasks\":[],\"deliverable\":\"一个 demo\"}],"
                        + "\"tracks\":[],\"cadence\":[]}");

        mockMvc.perform(post("/api/career/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetJob\":\"Java 后端\",\"resumeText\":\"熟悉 Spring Boot\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.whyFit").value("技术栈高度吻合"))
                .andExpect(jsonPath("$.data.thirtyDayPlan[0].deliverable").value("一个 demo"));
    }

    @Test
    @DisplayName("POST /api/career/plan：targetJob 为空返回 400")
    void plan_blankTargetJob() throws Exception {
        loginAs("user-1");

        mockMvc.perform(post("/api/career/plan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeText\":\"简历\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }
}
