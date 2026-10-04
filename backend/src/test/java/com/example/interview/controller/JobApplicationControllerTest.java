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
    private com.example.interview.service.job.ApplicationImportService applicationImportService;

    /** v1.61.0：时序视图服务（新端点 /timeline 的依赖，@WebMvcTest 下必须补 @MockBean） */
    @MockBean
    private com.example.interview.service.job.ApplicationTimelineService applicationTimelineService;
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
    @DisplayName("DELETE /api/application/{id}：不存在返回 HTTP 404 + code 404（v1.48.0 统一口径）")
    void remove_notFound() throws Exception {
        loginAs("user-1");
        when(applicationService.remove("user-1", 9L)).thenReturn(false);

        mockMvc.perform(delete("/api/application/9"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    // ── 批量导入（第三批 F）──

    @Test
    @DisplayName("POST /api/application/import：dryRun 返回四类计数（code=200）")
    void import_dryRun_ok() throws Exception {
        loginAs("user-1");
        when(applicationImportService.run(eq("user-1"), eq("csv"), any(), eq(true)))
                .thenReturn(Map.of("dryRun", true, "canApply", true,
                        "summary", Map.of("added", 2, "merged", 0, "skipped", 1, "errors", 0)));

        mockMvc.perform(post("/api/application/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"csv\",\"dryRun\":true,\"rows\":[{\"companyName\":\"字节\",\"title\":\"Java\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.summary.added").value(2));
    }

    @Test
    @DisplayName("POST /api/application/import：rows 为空返回 400")
    void import_emptyRows_returns400() throws Exception {
        loginAs("user-1");
        mockMvc.perform(post("/api/application/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dryRun\":true,\"rows\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("POST /api/application/import：超过 200 行返回 400")
    void import_tooManyRows_returns400() throws Exception {
        loginAs("user-1");
        StringBuilder sb = new StringBuilder("{\"dryRun\":true,\"rows\":[");
        for (int i = 0; i < 201; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\"companyName\":\"c\",\"title\":\"t\"}");
        }
        sb.append("]}");

        mockMvc.perform(post("/api/application/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sb.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("单次最多导入 200 行"));
    }

    @Test
    @DisplayName("POST /api/application/import：dryRun 含致命行 → HTTP 200 + canApply:false + 致命明细")
    void import_dryRunFatal_returns200WithDetails() throws Exception {
        loginAs("user-1");
        when(applicationImportService.run(eq("user-1"), eq("paste"), any(), eq(true)))
                .thenReturn(Map.of("dryRun", true, "canApply", false,
                        "summary", Map.of("added", 0, "merged", 0, "skipped", 0, "errors", 1),
                        "fatalErrors", List.of(Map.of("index", 0, "message", "缺少公司名称"))));

        mockMvc.perform(post("/api/application/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"paste\",\"dryRun\":true,\"rows\":[{\"title\":\"无公司\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.canApply").value(false))
                .andExpect(jsonPath("$.data.fatalErrors[0].message").value("缺少公司名称"));
    }

    @Test
    @DisplayName("POST /api/application/import：apply 含致命行 → HTTP 400 且不写库")
    void import_applyFatal_returns400() throws Exception {
        loginAs("user-1");
        when(applicationImportService.run(eq("user-1"), eq("paste"), any(), eq(false)))
                .thenReturn(Map.of("dryRun", false, "canApply", false, "applied", false,
                        "message", "存在致命错误行（缺少公司名称/岗位名称），已取消导入，未写入任何数据"));

        mockMvc.perform(post("/api/application/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"paste\",\"dryRun\":false,\"rows\":[{\"title\":\"无公司\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("致命错误行")))
                .andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    @DisplayName("POST /api/application/import：正常 apply（无致命行）→ HTTP 200 + applied:true + 四类计数")
    void import_apply_ok() throws Exception {
        // 护栏：apply 的「有致命行 → 抛 400」条件分支写在 return 之前。
        // 若该条件写得过宽（如 Boolean.FALSE.equals 恒真），正常导入会被误判成 400——
        // 这是用户可见的数据写入失败。本用例锁死「正常路径仍 200 且 applied:true」。
        loginAs("user-1");
        when(applicationImportService.run(eq("user-1"), eq("csv"), any(), eq(false)))
                .thenReturn(Map.of("dryRun", false, "canApply", true, "applied", true,
                        "summary", Map.of("added", 2, "merged", 1, "skipped", 1, "errors", 0)));

        mockMvc.perform(post("/api/application/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"source\":\"csv\",\"dryRun\":false,\"rows\":[{\"companyName\":\"字节\",\"title\":\"Java\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.applied").value(true))
                .andExpect(jsonPath("$.data.summary.added").value(2))
                .andExpect(jsonPath("$.data.summary.merged").value(1))
                .andExpect(jsonPath("$.data.summary.skipped").value(1))
                .andExpect(jsonPath("$.data.summary.errors").value(0));
    }

    // ───────── v1.61.0 投递 ↔ 面试时序视图 ─────────

    @Test
    @DisplayName("GET /api/application/timeline：透传服务结果，且按 JWT 用户隔离（不透传请求体 userId）")
    void timeline_returnsServiceResultScopedToJwtUser() throws Exception {
        loginAs("user-7");
        // 用 LinkedHashMap 而非 Map.of —— Map.of 不接受 null 值，
        // 而「无样本时 avgDaysToInterview 为 null」正是本用例要验证的行为。
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("appliedCount", 1L);
        stats.put("interviewSampleSize", 0);
        stats.put("avgDaysToInterview", null);

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("nodes", List.of(Map.of("kind", "APPLIED", "at", "2026-10-01T10:00")));
        payload.put("stats", stats);
        when(applicationTimelineService.timeline(eq("user-7"))).thenReturn(payload);

        mockMvc.perform(get("/api/application/timeline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.nodes[0].kind").value("APPLIED"))
                .andExpect(jsonPath("$.data.stats.appliedCount").value(1))
                // 无样本时统计值为 null，不得渲染成 0（无数据 ≠ 0）
                .andExpect(jsonPath("$.data.stats.avgDaysToInterview").doesNotExist());

        // 归属来源必须是 JWT subject；若实现改从请求体取 userId，这条会失败
        org.mockito.Mockito.verify(applicationTimelineService).timeline("user-7");
        org.mockito.Mockito.verifyNoMoreInteractions(applicationTimelineService);
    }
}
