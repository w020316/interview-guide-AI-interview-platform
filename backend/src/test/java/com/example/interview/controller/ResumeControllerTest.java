package com.example.interview.controller;

import com.example.interview.security.JwtUtil;
import com.example.interview.service.ResumeAnalysisService;
import com.example.interview.service.ResumeParseService;
import com.example.interview.service.ResumeService;
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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ResumeController} MockMvc 集成测试
 *
 * <p>覆盖 /api/resume/analyze、/api/resume/optimize 的入参校验，以及 /api/resume/import-url 的 SSRF 防御。
 * upload 端点涉及 MultipartFile 解析，本轮不覆盖（留待 Testcontainers 集成测试）。
 */
@WebMvcTest(controllers = ResumeController.class,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration")
class ResumeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ResumeAnalysisService resumeAnalysisService;

    @MockBean
    private ResumeParseService resumeParseService;

    @MockBean
    private ResumeService resumeService;

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

    @Nested
    @DisplayName("POST /api/resume/analyze 简历分析")
    class Analyze {

        @Test
        @DisplayName("简历内容为空返回 400")
        void analyze_emptyResume_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "resumeText", "", "targetJob", "Java 后端"));

            mockMvc.perform(post("/api/resume/analyze")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("简历内容不能为空"));
        }

        @Test
        @DisplayName("简历内容缺失返回 400")
        void analyze_missingResume_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of("targetJob", "Java 后端"));

            mockMvc.perform(post("/api/resume/analyze")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("简历内容不能为空"));
        }

        @Test
        @DisplayName("合法入参返回 200 + 分析结果")
        void analyze_validInput_returns200() throws Exception {
            when(resumeAnalysisService.analyze(USER_ID, "我的简历", "Java 后端"))
                    .thenReturn("{\"score\":80}");

            String body = objectMapper.writeValueAsString(Map.of(
                    "resumeText", "我的简历", "targetJob", "Java 后端"));

            mockMvc.perform(post("/api/resume/analyze")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").exists());
        }
    }

    @Nested
    @DisplayName("POST /api/resume/optimize 优化简历生成")
    class Optimize {

        @Test
        @DisplayName("简历与分析均为空返回 400（简历优先）")
        void optimize_emptyResume_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "resumeText", "", "analysis", ""));

            mockMvc.perform(post("/api/resume/optimize")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("简历内容不能为空，请先上传简历或粘贴文本完成分析"));
        }

        @Test
        @DisplayName("简历存在但分析为空返回 400")
        void optimize_emptyAnalysis_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of(
                    "resumeText", "我的简历", "analysis", ""));

            mockMvc.perform(post("/api/resume/optimize")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("请先完成简历分析，再生成优化版简历"));
        }

        @Test
        @DisplayName("合法入参返回 200 + 优化简历 Markdown")
        void optimize_validInput_returns200() throws Exception {
            when(resumeAnalysisService.generateOptimizedResume(
                    USER_ID, "我的简历", "Java 后端", "{\"score\":80}"))
                    .thenReturn("# 优化后的简历\n\n正文…");

            String body = objectMapper.writeValueAsString(Map.of(
                    "resumeText", "我的简历",
                    "targetJob", "Java 后端",
                    "analysis", "{\"score\":80}"));

            mockMvc.perform(post("/api/resume/optimize")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").exists());
        }
    }

    @Nested
    @DisplayName("POST /api/resume/import-url URL 导入（SSRF 防御）")
    class ImportUrl {

        @Test
        @DisplayName("URL 为空返回 400")
        void importUrl_emptyUrl_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of("url", ""));

            mockMvc.perform(post("/api/resume/import-url")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("URL 不能为空"));
        }

        @Test
        @DisplayName("非 http/https 协议返回 400")
        void importUrl_invalidProtocol_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of("url", "ftp://example.com/resume"));

            mockMvc.perform(post("/api/resume/import-url")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("请输入有效的 URL（以 http:// 或 https:// 开头）"));
        }

        @Test
        @DisplayName("内网地址 127.0.0.1 被拦截返回 400")
        void importUrl_localhost_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of("url", "http://127.0.0.1/admin"));

            mockMvc.perform(post("/api/resume/import-url")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("不支持访问内网地址"));
        }

        @Test
        @DisplayName("内网地址 192.168.x 被拦截返回 400")
        void importUrl_privateNetwork_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of("url", "http://192.168.1.1/internal"));

            mockMvc.perform(post("/api/resume/import-url")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("不支持访问内网地址"));
        }
    }
}
