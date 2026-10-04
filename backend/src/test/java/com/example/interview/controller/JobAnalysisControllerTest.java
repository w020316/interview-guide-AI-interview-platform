package com.example.interview.controller;

import com.example.interview.security.JwtUtil;
import com.example.interview.service.JobAnalysisService;
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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    /**
     * v1.58.0 新增依赖：Controller 现在会先尝试 ATS 公开 API 解析岗位链接。
     * @WebMvcTest 只装配 Controller 层，这类 Service 必须显式 mock 掉，
     * 否则上下文加载失败（NoSuchBeanDefinitionException）——新增注入后忘了补 mock
     * 会让整个测试类报错，而不是给出「缺哪个 bean」的友好提示，容易误判成业务失败。
     */
    @MockBean
    private com.example.interview.service.job.AtsJobResolver atsJobResolver;

    @MockBean
    private com.example.interview.service.job.JobPageFetcher jobPageFetcher;

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

    /**
     * v1.58.0：从岗位链接导入 JD（竞品 #14）。
     *
     * <p>重点覆盖**两条路径的分流**——ATS 公开 API 优先，失败才退化到 HTML 抓取。
     * 分流写错会导致「明明能从官方接口拿到原文，却去抓 SPA 空壳然后告诉用户抓不到」。
     */
    @Nested
    @DisplayName("POST /api/job/import-url")
    class ImportUrl {

        @Test
        @DisplayName("空链接 → code 400")
        void importUrl_blankUrl_returns400() throws Exception {
            String body = objectMapper.writeValueAsString(Map.of("url", ""));
            mockMvc.perform(post("/api/job/import-url")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(400))
                    .andExpect(jsonPath("$.message").value("链接不能为空"));
        }

        @Test
        @DisplayName("ATS 命中时走官方接口：返回原文，且不经 AI 抽取")
        void importUrl_atsHit_returnsOfficialContent() throws Exception {
            when(atsJobResolver.resolve(anyString())).thenReturn(java.util.Optional.of(
                    new com.example.interview.service.job.AtsJobResolver.ResolvedJob(
                            "https://jobs.ashbyhq.com/ramp/abc", "Ashby",
                            "Security Engineer, Cloud", "Ramp", "New York, NY (HQ)",
                            "ABOUT RAMP\nResponsibilities: secure our cloud.")));

            String body = objectMapper.writeValueAsString(
                    Map.of("url", "https://jobs.ashbyhq.com/ramp/34413f8d-26bf-4bbc-8ade-eb309a0e2245"));
            mockMvc.perform(post("/api/job/import-url")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    // 字段直接来自公开接口
                    .andExpect(jsonPath("$.data.extracted.jobTitle").value("Security Engineer, Cloud"))
                    .andExpect(jsonPath("$.data.extracted.company").value("Ramp"))
                    .andExpect(jsonPath("$.data.extracted.jobDescription").value(
                            org.hamcrest.Matchers.containsString("secure our cloud")))
                    .andExpect(jsonPath("$.data.source").value("Ashby"))
                    // 关键：ATS 路径不调 AI，也不碰 HTML 抓取
                    .andExpect(jsonPath("$.data.sourceUrl").value("https://jobs.ashbyhq.com/ramp/abc"));

            verify(atsJobResolver).resolve(anyString());
            verifyNoInteractions(jobPageFetcher);
            verifyNoInteractions(jobAnalysisService);
        }

        @Test
        @DisplayName("ATS 未命中 → 退化到 HTML 抓取；抓取失败时透出 reason 供前端分型提示")
        void importUrl_atsMiss_fallsBackToFetch_withReason() throws Exception {
            when(atsJobResolver.resolve(anyString())).thenReturn(java.util.Optional.empty());
            when(jobPageFetcher.fetch(anyString())).thenReturn(
                    new com.example.interview.service.job.JobPageFetcher.FetchedPage(
                            com.example.interview.service.job.JobPageFetcher.Outcome.SPA_OR_EMPTY,
                            com.example.interview.service.job.JobPageFetcher.Reason.JS_RENDERED,
                            "https://www.zhipin.com/x", null, "该页面是动态渲染的，请手动复制"));

            String body = objectMapper.writeValueAsString(Map.of("url", "https://www.zhipin.com/x"));
            mockMvc.perform(post("/api/job/import-url")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(422))
                    .andExpect(jsonPath("$.message").value("该页面是动态渲染的，请手动复制"))
                    // reason 必须透出：前端据此给不同的「下一步动作」提示
                    .andExpect(jsonPath("$.data.reason").value("JS_RENDERED"));
        }

        @Test
        @DisplayName("ATS 未命中且抓取成功 → AI 抽取，返回字符串形态的 extracted")
        void importUrl_fetchOk_usesAiExtraction() throws Exception {
            when(atsJobResolver.resolve(anyString())).thenReturn(java.util.Optional.empty());
            when(jobPageFetcher.fetch(anyString())).thenReturn(
                    new com.example.interview.service.job.JobPageFetcher.FetchedPage(
                            com.example.interview.service.job.JobPageFetcher.Outcome.OK,
                            com.example.interview.service.job.JobPageFetcher.Reason.NONE,
                            "https://example.com/job", "岗位职责：负责后端开发".repeat(50), null));
            when(jobAnalysisService.extractJobFromPage(anyString()))
                    .thenReturn("{\"jobTitle\":\"后端工程师\",\"company\":\"某公司\",\"jobDescription\":\"职责…\"}");

            String body = objectMapper.writeValueAsString(Map.of("url", "https://example.com/job"));
            mockMvc.perform(post("/api/job/import-url")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.extracted").isString())
                    .andExpect(jsonPath("$.data.source").value("web"));
        }
    }
}
