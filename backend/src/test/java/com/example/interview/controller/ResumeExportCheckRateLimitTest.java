package com.example.interview.controller;

import com.example.interview.interceptor.RateLimitInterceptor;
import com.example.interview.security.JwtUtil;
import com.example.interview.service.ResumeAnalysisService;
import com.example.interview.service.ResumeExportCheckService;
import com.example.interview.service.ResumeExportCheckService.ExportCheckReport;
import com.example.interview.service.ResumeParseService;
import com.example.interview.service.ResumeService;
import com.example.interview.util.PerUserRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 导出体检的限流口径（20 次/分钟/用户）。
 *
 * <p>这条用例守的不是数字本身，而是「控制器里真的接了限流器」——
 * 只声明字段却忘了调用 {@code allow()} 时，功能测试全绿、线上仍然可以被脚本无限打，
 * 这类缺陷静态审查看不出来。
 *
 * <p>与 {@code InterviewControllerRateLimitTest} 同一做法：临时换成配额 1 的实例，
 * 用完原样放回原引用（不触碰其内部计数），避免污染共用上下文的 {@link ResumeControllerTest}。
 */
@WebMvcTest(controllers = ResumeController.class,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration")
@DisplayName("ResumeController 导出体检限流 429 口径")
class ResumeExportCheckRateLimitTest {

    private static final String USER_ID = "1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ResumeController resumeController;

    @MockBean
    private ResumeAnalysisService resumeAnalysisService;

    @MockBean
    private ResumeParseService resumeParseService;

    @MockBean
    private ResumeService resumeService;

    @MockBean
    private com.example.interview.service.ResumeImageOcrService resumeImageOcrService;

    @MockBean
    private ResumeExportCheckService resumeExportCheckService;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private RateLimitInterceptor rateLimitInterceptor;

    private Object originalLimiter;

    @BeforeEach
    void setUp() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID, null, List.of()));
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);

        originalLimiter = ReflectionTestUtils.getField(resumeController, "exportCheckLimiter");
        ReflectionTestUtils.setField(resumeController, "exportCheckLimiter",
                new PerUserRateLimiter(1, 60_000L));
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(resumeController, "exportCheckLimiter", originalLimiter);
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("配额耗尽后返回真 HTTP 429，且超限请求不会进入解析服务")
    void exportCheck_beyondQuota_returnsRealHttp429() throws Exception {
        when(resumeExportCheckService.check(any(MultipartFile.class)))
                .thenReturn(new ExportCheckReport("pass", "全部 7 项检查通过", 1, 600, List.of()));

        MockMultipartFile file = new MockMultipartFile(
                "file", "resume.pdf", "application/pdf", "%PDF-1.7 ok".getBytes());

        mockMvc.perform(multipart("/api/resume/export-check").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(multipart("/api/resume/export-check").file(file))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.message").value("体检太频繁了，请一分钟后再试"));

        verify(resumeExportCheckService, times(1)).check(any(MultipartFile.class));
    }
}
