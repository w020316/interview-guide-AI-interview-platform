package com.example.interview.controller;

import com.example.interview.config.SecurityConfig;
import com.example.interview.security.JwtAuthFilter;
import com.example.interview.security.JwtUtil;
import com.example.interview.service.AdminService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理后台权限测试（v1.31.4）
 * <p>@Import(SecurityConfig) 加载完整 Security 链（含 @EnableMethodSecurity），验证：
 * - 普通用户访问 /api/admin/** → 403
 * - 管理员（ROLE_ADMIN）→ 200
 */
@WebMvcTest(controllers = AdminController.class,
        properties = "app.cors.allowed-origins=http://localhost:5173")
@Import({SecurityConfig.class, JwtAuthFilter.class})
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminService adminService;

    @MockBean
    private JwtUtil jwtUtil;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("1", null, List.of(new SimpleGrantedAuthority(role))));
    }

    @Test
    @DisplayName("普通用户访问管理接口被拒 403")
    void user_forbidden() throws Exception {
        loginAs("ROLE_USER");
        mockMvc.perform(get("/api/admin/overview"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("管理员可访问总览 200")
    void admin_allowed() throws Exception {
        loginAs("ROLE_ADMIN");
        mockMvc.perform(get("/api/admin/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    // ── 全端点（管理员身份） ──

    private void loginAsAdmin() {
        loginAs("ROLE_ADMIN");
    }

    @Test
    @DisplayName("POST /jobs/refresh: 返回刷新统计")
    void admin_refreshJobs() throws Exception {
        loginAsAdmin();
        when(adminService.refreshJobs())
                .thenReturn(new com.example.interview.service.job.JobAgentService.RefreshResult(2, 1, 1, 3, 1));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/admin/jobs/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.upserted").value(2))
                .andExpect(jsonPath("$.data.expired").value(3));
    }

    @Test
    @DisplayName("GET /sources: 返回数据源健康视图（v1.37.0）")
    void admin_sources() throws Exception {
        loginAs("ROLE_ADMIN");
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("platform", "行业精选");
        row.put("enabled", true);
        row.put("total", 53L);
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("items", List.of(row));
        payload.put("count", 1);
        payload.put("enabledCount", 1L);
        when(adminService.sources()).thenReturn(payload);

        mockMvc.perform(get("/api/admin/sources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.count").value(1))
                .andExpect(jsonPath("$.data.items[0].platform").value("行业精选"))
                .andExpect(jsonPath("$.data.items[0].enabled").value(true));
    }

    @Test
    @DisplayName("GET /jobs: 返回分页岗位列表（含失效）")
    void admin_listJobs() throws Exception {
        loginAsAdmin();
        var job = com.example.interview.entity.JobPostingEntity.builder()
                .id(1L).title("Java 后端").companyName("某公司").active(false).build();
        when(adminService.listJobs(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(job)));

        mockMvc.perform(get("/api/admin/jobs")
                        .param("keyword", "java")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].title").value("Java 后端"));
    }

    @Test
    @DisplayName("POST /jobs/{id}/deactivate 与 /activate: 返回成功业务码")
    void admin_deactivateAndActivateJob() throws Exception {
        loginAsAdmin();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/admin/jobs/1/deactivate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/admin/jobs/1/activate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        org.mockito.Mockito.verify(adminService).deactivateJob(1L);
        org.mockito.Mockito.verify(adminService).activateJob(1L);
    }

    @Test
    @DisplayName("DELETE /jobs/{id}: 返回成功业务码")
    void admin_deleteJob() throws Exception {
        loginAsAdmin();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/admin/jobs/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        org.mockito.Mockito.verify(adminService).deleteJob(1L);
    }

    @Test
    @DisplayName("GET /users: 返回脱敏用户分页列表")
    void admin_listUsers() throws Exception {
        loginAsAdmin();
        var view = new AdminService.UserView(7L, "alice", "a@x.com", null, true);
        when(adminService.listUsers(any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(view)));

        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].username").value("alice"))
                .andExpect(jsonPath("$.data.items[0].banned").value(true));
    }

    @Test
    @DisplayName("POST /users/{id}/ban 与 /unban: 返回成功业务码")
    void admin_banAndUnbanUser() throws Exception {
        loginAsAdmin();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/admin/users/7/ban"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/admin/users/7/unban"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        // P2-07：封禁需带当前管理员 ID（供服务端拦截「禁用自己 / 最后一个管理员」）
        org.mockito.Mockito.verify(adminService).banUser(
                org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(adminService).unbanUser(7L);
    }

    @Test
    @DisplayName("GET /metrics: 返回系统指标")
    void admin_metrics() throws Exception {
        loginAsAdmin();
        when(adminService.metrics()).thenReturn(java.util.Map.of(
                "aiCalls", java.util.Map.of("resume", 3L),
                "jvm", java.util.Map.of("totalMb", 512)));

        mockMvc.perform(get("/api/admin/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.aiCalls.resume").value(3))
                .andExpect(jsonPath("$.data.jvm.totalMb").value(512));
    }
}