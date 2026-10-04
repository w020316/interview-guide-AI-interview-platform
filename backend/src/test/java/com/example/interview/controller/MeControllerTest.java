package com.example.interview.controller;

import com.example.interview.common.ConflictException;
import com.example.interview.interceptor.RateLimitInterceptor;
import com.example.interview.security.JwtUtil;
import com.example.interview.service.BackupService;
import com.example.interview.service.UserAiKeyService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link MeController} MockMvc 测试（第三批 C）。
 */
@WebMvcTest(controllers = MeController.class,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration")
class MeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BackupService backupService;

    @MockBean
    private UserAiKeyService userAiKeyService;

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
    @DisplayName("GET /api/me/export 返回导出结构（code=200）")
    void export_returns200() throws Exception {
        when(backupService.export(any(), anyBoolean())).thenReturn(Map.of(
                "schemaVersion", "3",
                "dataFingerprint", "abcdef0123456789",
                "counts", Map.of("resumes", 0),
                "data", Map.of()));

        mockMvc.perform(get("/api/me/export"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.schemaVersion").value("3"))
                .andExpect(jsonPath("$.data.dataFingerprint").value("abcdef0123456789"));
    }

    @Test
    @DisplayName("POST /api/me/import apply 成功返回 applied:true")
    void import_apply_returns200() throws Exception {
        when(backupService.importData(any(), any())).thenReturn(Map.of(
                "applied", true,
                "importId", "imp-1",
                "summary", Map.of("resumes", Map.of("added", 1))));

        mockMvc.perform(post("/api/me/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"merge\",\"dryRun\":false,\"importId\":\"imp-1\",\"payload\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.applied").value(true));
    }

    @Test
    @DisplayName("POST /api/me/import 指纹冲突 → HTTP 409 + code 409（第三批 C）")
    void import_conflict_returns409() throws Exception {
        when(backupService.importData(any(), any()))
                .thenThrow(new ConflictException("数据在导出后已发生变化（指纹不符）"));

        mockMvc.perform(post("/api/me/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"replace\",\"dryRun\":false,\"force\":false,\"payload\":{}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("数据在导出后已发生变化（指纹不符）"));
    }

    @Test
    @DisplayName("POST /api/me/import 参数非法 → HTTP 400 + code 400")
    void import_badRequest_returns400() throws Exception {
        when(backupService.importData(any(), any()))
                .thenThrow(new IllegalArgumentException("mode 非法，合法取值：merge / replace"));

        mockMvc.perform(post("/api/me/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"bad\",\"payload\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }
}
