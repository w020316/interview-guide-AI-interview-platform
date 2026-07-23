package com.example.interview.controller;

import com.example.interview.security.JwtUtil;
import com.example.interview.service.JobAnalysisService;
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
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link JobAnalysisController} MockMvc 集成测试
 *
 * <p>覆盖 /api/job/analyze、/api/job/gap、/api/job/letter 三个端点的入参校验与正常路径。
 * 通过 @MockBean 隔离 JobAnalysisService（不真正调用 AI）。
 * Controller 内部 {@code currentUserId()} 读取 SecurityContext，故 @BeforeEach 手动注入认证主体。
 */
@WebMvcTest(controllers = JobAnalysisController.class,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration")
class JobAnalysisControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JobAnalysisService jobAnalysisService;

    @MockBean
    private JwtUtil jwtUtil;

    private static final String USER_ID = "1";

    @BeforeEach
    void setUpSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID, null, List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("POST /api/job/analyze JD 岗位分析")
    class Analyze {

        @Test
        @DisplayName("岗位描述为空返回 400")
        void analyze_emptyJobDescription_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of("jobDescription", ""));

            mockMvc.perform(post("/api/job/analyze")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("岗位描述不能为空"));
        }

        @Test
        @DisplayName("岗位描述缺失返回 400")
        void analyze_missingJobDescription_returns400() throws Exception {
            String body = "{}";

            mockMvc.perform(post("/api/job/analyze")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("岗位描述不能为空"));
        }

        @Test
        @DisplayName("合法入参返回 200 + 分析结果")
        void analyze_validInput_returns200() throws Exception {
            when(jobAnalysisService.analyzeJobDescription("Java 后端，3 年经验"))
                    .thenReturn("{\"summary\":\"分析结果\"}");

            String body = objectMapper.writeValueAsString(
                    Map.of("jobDescription", "Java 后端，3 年经验"));

            mockMvc.perform(post("/api/job/analyze")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").exists());
        }
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("POST /api/job/gap 差距诊断")
    class Gap {

        @Test
        @DisplayName("简历内容为空返回 400")
        void gap_emptyResume_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "resumeText", "", "jobDescription", "Java 后端"));

            mockMvc.perform(post("/api/job/gap")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("简历内容不能为空"));
        }

        @Test
        @DisplayName("岗位描述为空返回 400")
        void gap_emptyJobDescription_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "resumeText", "我的简历", "jobDescription", ""));

            mockMvc.perform(post("/api/job/gap")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("岗位描述不能为空"));
        }

        @Test
        @DisplayName("合法入参返回 200 + 差距诊断结果")
        void gap_validInput_returns200() throws Exception {
            when(jobAnalysisService.diagnoseGap("我的简历", "Java 后端"))
                    .thenReturn("{\"gap\":\"分析结果\"}");

            String body = objectMapper.writeValueAsString(Map.of(
                    "resumeText", "我的简历", "jobDescription", "Java 后端"));

            mockMvc.perform(post("/api/job/gap")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").exists());
        }
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("POST /api/job/letter 求职信生成")
    class Letter {

        @Test
        @DisplayName("简历内容为空返回 400")
        void letter_emptyResume_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "resumeText", "", "jobDescription", "Java 后端", "type", "coverLetter"));

            mockMvc.perform(post("/api/job/letter")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("简历内容不能为空"));
        }

        @Test
        @DisplayName("合法入参返回 200 + 求职信")
        void letter_validInput_returns200() throws Exception {
            when(jobAnalysisService.generateLetter("我的简历", "Java 后端", "coverLetter"))
                    .thenReturn("求职信正文");

            String body = objectMapper.writeValueAsString(Map.of(
                    "resumeText", "我的简历", "jobDescription", "Java 后端", "type", "coverLetter"));

            mockMvc.perform(post("/api/job/letter")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").value("求职信正文"));
        }
    }
}
