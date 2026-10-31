package com.example.interview.controller;

import com.example.interview.common.ConflictException;
import com.example.interview.common.ResourceNotFoundException;
import com.example.interview.interceptor.RateLimitInterceptor;
import com.example.interview.service.ResumeAnalysisService;
import com.example.interview.service.ResumeImageOcrService;
import com.example.interview.service.ResumeParseService;
import com.example.interview.service.ResumeService;
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

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code DELETE /api/resume/{id}} 的 MockMvc 测试（G2，2026-10-18）。
 *
 * <p>只测**契约**：路径、参数绑定、状态码映射。删除语义（外键顺序、拒绝级联）在
 * {@link com.example.interview.service.ResumeDeleteServiceTest} 里测，两边不重复。
 *
 * <p>为什么单独成类：{@link ResumeControllerTest} 已覆盖 6 个端点、体量很大，
 * 再往里塞会让人分不清「这次改动动了哪些契约」。
 */
@WebMvcTest(controllers = ResumeController.class,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration")
@DisplayName("DELETE /api/resume/{id} 删除简历")
class ResumeDeleteControllerTest {

    private static final String USER_ID = "1";

    @Autowired
    private MockMvc mockMvc;

    @MockBean private ResumeService resumeService;
    @MockBean private ResumeAnalysisService resumeAnalysisService;
    @MockBean private ResumeParseService resumeParseService;
    @MockBean private ResumeImageOcrService resumeImageOcrService;
    @MockBean private com.example.interview.service.ResumeExportCheckService resumeExportCheckService;
    @MockBean private com.example.interview.service.RemotePdfFetcher remotePdfFetcher;
    @MockBean private com.example.interview.util.SsrfGuard ssrfGuard;

    /** @WebMvcTest 会装配 WebMvcConfigurer 注册的拦截器；boolean 默认 false → 表现为 503 */
    @MockBean private RateLimitInterceptor rateLimitInterceptor;

    /**
     * @WebMvcTest 还会装配 JwtAuthFilter（Filter bean 在切片范围内），它依赖 JwtUtil。
     * 不提供这个 mock，上下文启动就会失败：No qualifying bean of type 'JwtUtil'。
     */
    @MockBean private com.example.interview.security.JwtUtil jwtUtil;

    @BeforeEach
    void setUp() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID, null, List.of()));
        when(rateLimitInterceptor.preHandle(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("无派生会话 → 200，deletedSessions=0，cascade 默认 false")
    void delete_noSessions_returns200() throws Exception {
        when(resumeService.deleteForUser(eq(7L), eq(USER_ID), anyBoolean())).thenReturn(0);

        mockMvc.perform(delete("/api/resume/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.deletedSessions").value(0));

        verify(resumeService).deleteForUser(7L, USER_ID, false);
    }

    @Test
    @DisplayName("显式 cascade=true → 透传给服务层，返回值带删除的会话数")
    void delete_withCascade_passesFlagThrough() throws Exception {
        when(resumeService.deleteForUser(eq(7L), eq(USER_ID), eq(true))).thenReturn(3);

        mockMvc.perform(delete("/api/resume/7").param("cascade", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deletedSessions").value(3));

        verify(resumeService).deleteForUser(7L, USER_ID, true);
    }

    @Test
    @DisplayName("被面试引用且未级联 → HTTP 409（ConflictException 走 @ExceptionHandler）")
    void delete_conflict_returns409() throws Exception {
        when(resumeService.deleteForUser(eq(7L), eq(USER_ID), anyBoolean()))
                .thenThrow(new ConflictException("该简历已用于 2 场面试，需确认后一并删除"));

        mockMvc.perform(delete("/api/resume/7"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("该简历已用于 2 场面试，需确认后一并删除"));
    }

    @Test
    @DisplayName("简历不存在 → 404")
    void delete_notFound_returns404() throws Exception {
        when(resumeService.deleteForUser(eq(999L), eq(USER_ID), anyBoolean()))
                .thenThrow(new ResourceNotFoundException("简历不存在：999"));

        mockMvc.perform(delete("/api/resume/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }
}
