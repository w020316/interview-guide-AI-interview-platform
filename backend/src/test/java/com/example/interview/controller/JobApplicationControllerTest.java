package com.example.interview.controller;

import com.example.interview.entity.JobApplicationEntity;
import com.example.interview.entity.JobPostingEntity;
import com.example.interview.interceptor.RateLimitInterceptor;
import com.example.interview.security.JwtUtil;
import com.example.interview.service.JobFavoriteService;
import com.example.interview.service.career.TailoredResumeService;
import com.example.interview.service.job.JobAgentService;
import com.example.interview.service.job.JobApplicationService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link JobApplicationController} MockMvc 测试（v1.35.0 投递台账）
 *
 * <p>验证：列表/看板返回结构、加入台账参数校验、确认投递、状态推进、定制简历、移除。
 */
@WebMvcTest(controllers = JobApplicationController.class,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration")
class JobApplicationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JobApplicationService applicationService;
    @MockBean
    private JobAgentService jobAgentService;
    @MockBean
    private JobFavoriteService favoriteService;
    @MockBean
    private TailoredResumeService tailoredResumeService;
    @MockBean
    private JwtUtil jwtUtil;
    @MockBean
    private RateLimitInterceptor rateLimitInterceptor;

    private JobApplicationEntity app(Long id, String status) {
        return JobApplicationEntity.builder()
                .id(id).userId("user-1").jobId(1L)
                .title("Java 后端").companyName("腾讯").status(status)
                .build();
    }

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
    @DisplayName("GET /api/application/list：返回投递列表与状态标签")
    void list_ok() throws Exception {
        loginAs("user-1");
        when(applicationService.listByUser("user-1"))
                .thenReturn(List.of(app(1L, JobApplicationEntity.STATUS_PLANNED)));

        mockMvc.perform(get("/api/application/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].title").value("Java 后端"))
                .andExpect(jsonPath("$.data.statusLabels.PLANNED").value("待投递"));
    }

    @Test
    @DisplayName("GET /api/application/board：返回看板数据")
    void board_ok() throws Exception {
        loginAs("user-1");
        when(applicationService.board("user-1")).thenReturn(Map.of("total", 2, "followUpCount", 1));

        mockMvc.perform(get("/api/application/board"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.followUpCount").value(1));
    }

    @Test
    @DisplayName("POST /api/application/draft：按 jobId 加入台账")
    void draft_byJobId() throws Exception {
        loginAs("user-1");
        JobPostingEntity job = JobPostingEntity.builder()
                .id(1L).platform("内置精选").externalId("e1")
                .title("Java 后端").companyName("腾讯").build();
        when(jobAgentService.findById(1L)).thenReturn(job);
        when(applicationService.addFromJob(eq("user-1"), eq(job), any()))
                .thenReturn(app(1L, JobApplicationEntity.STATUS_PLANNED));

        mockMvc.perform(post("/api/application/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobId\":1,\"note\":\"内推\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PLANNED"));
    }

    @Test
    @DisplayName("POST /api/application/draft：jobId 与 favoriteId 都缺失时返回 400")
    void draft_missingParams() throws Exception {
        loginAs("user-1");

        mockMvc.perform(post("/api/application/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("POST /api/application/draft：岗位不存在返回 404")
    void draft_jobNotFound() throws Exception {
        loginAs("user-1");
        when(jobAgentService.findById(99L)).thenReturn(null);

        mockMvc.perform(post("/api/application/draft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobId\":99}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("POST /api/application/{id}/confirm：确认投递")
    void confirm_ok() throws Exception {
        loginAs("user-1");
        when(applicationService.confirmApply("user-1", 1L))
                .thenReturn(app(1L, JobApplicationEntity.STATUS_APPLIED));

        mockMvc.perform(post("/api/application/1/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPLIED"));
    }

    @Test
    @DisplayName("POST /api/application/{id}/confirm：非本人记录返回 404")
    void confirm_notOwned() throws Exception {
        loginAs("user-1");
        when(applicationService.confirmApply("user-1", 1L)).thenReturn(null);

        mockMvc.perform(post("/api/application/1/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    @DisplayName("POST /api/application/{id}/status：非法状态返回 400")
    void updateStatus_invalid() throws Exception {
        loginAs("user-1");
        when(applicationService.updateStatus(eq("user-1"), eq(1L), anyString(), any(), any())).thenReturn(null);

        mockMvc.perform(post("/api/application/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BAD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("POST /api/application/{id}/status：status 为空返回 400")
    void updateStatus_blank() throws Exception {
        loginAs("user-1");

        mockMvc.perform(post("/api/application/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("POST /api/application/{id}/tailor：生成定制简历并持久化")
    void tailor_ok() throws Exception {
        loginAs("user-1");
        when(applicationService.findOwned("user-1", 1L))
                .thenReturn(app(1L, JobApplicationEntity.STATUS_APPLIED));
        when(tailoredResumeService.tailor(anyString(), anyString(), anyString()))
                .thenReturn("{\"summary\":\"懂业务的 Java 后端\",\"rewrittenBullets\":[]}");
        when(applicationService.saveTailoredResume(eq("user-1"), eq(1L), anyString()))
                .thenReturn(app(1L, JobApplicationEntity.STATUS_APPLIED));

        mockMvc.perform(post("/api/application/1/tailor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeText\":\"熟悉 Java\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applicationId").value(1))
                .andExpect(jsonPath("$.data.tailoredResume.summary").value("懂业务的 Java 后端"));
    }

    @Test
    @DisplayName("POST /api/application/{id}/tailor：简历要点为空返回 400")
    void tailor_blankResume() throws Exception {
        loginAs("user-1");
        when(applicationService.findOwned("user-1", 1L))
                .thenReturn(app(1L, JobApplicationEntity.STATUS_APPLIED));

        mockMvc.perform(post("/api/application/1/tailor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("DELETE /api/application/{id}：移除记录")
    void remove_ok() throws Exception {
        loginAs("user-1");
        when(applicationService.remove("user-1", 1L)).thenReturn(true);

        mockMvc.perform(delete("/api/application/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.removed").value(true));
    }

    @Test
    @DisplayName("DELETE /api/application/{id}：不存在返回 404")
    void remove_notFound() throws Exception {
        loginAs("user-1");
        when(applicationService.remove("user-1", 9L)).thenReturn(false);

        mockMvc.perform(delete("/api/application/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }
}
