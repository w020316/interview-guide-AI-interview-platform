package com.example.interview.controller;

import com.example.interview.entity.JobPostingEntity;
import com.example.interview.repository.InterviewQuestionRepository;
import com.example.interview.repository.InterviewSessionRepository;
import com.example.interview.repository.ResumeRepository;
import com.example.interview.security.JwtUtil;
import com.example.interview.service.JobFavoriteService;
import com.example.interview.service.job.JobAgentService;
import com.example.interview.service.job.JobMatchService;
import com.example.interview.util.PerUserRateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link JobAgentController} MockMvc 测试——覆盖收藏之外的端点：
 * 岗位列表/详情/筛选元数据/简历匹配推荐/手动刷新（限流与管理员旁路）。
 * 收藏端点见 {@link JobFavoriteControllerTest}。
 */
@WebMvcTest(controllers = JobAgentController.class,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration")
@DisplayName("招聘信息智能体接口测试")
class JobAgentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JobAgentController controller;

    @MockBean
    private JobAgentService jobAgentService;

    @MockBean
    private JobFavoriteService jobFavoriteService;

    @MockBean
    private JobMatchService jobMatchService;

    @MockBean
    private ResumeRepository resumeRepository;

    @MockBean
    private InterviewSessionRepository sessionRepository;

    @MockBean
    private InterviewQuestionRepository questionRepository;

    @MockBean
    private JwtUtil jwtUtil;

    private static final String USER_ID = "1";

    @BeforeEach
    void setUpSecurityContext() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID, null, List.of()));
        resetRefreshLimiter();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** 清空共享限流器状态，保证刷新用例与执行顺序无关 */
    @SuppressWarnings("unchecked")
    private void resetRefreshLimiter() {
        PerUserRateLimiter limiter = (PerUserRateLimiter)
                ReflectionTestUtils.getField(controller, "refreshLimiter");
        ConcurrentHashMap<String, Object> buckets = (ConcurrentHashMap<String, Object>)
                ReflectionTestUtils.getField(limiter, "buckets");
        buckets.clear();
    }

    @Test
    @DisplayName("GET /api/jobs: 多条件搜索返回分页结果")
    void list_returnsPagedResults() throws Exception {
        JobPostingEntity job = JobPostingEntity.builder()
                .id(1L).platform("内置精选").externalId("e1")
                .title("Java 后端工程师").companyName("阿里巴巴").active(true).build();
        when(jobAgentService.search(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(job), PageRequest.of(0, 10), 25));

        mockMvc.perform(get("/api/jobs")
                        .param("keyword", "java")
                        .param("industry", "互联网")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(25))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.items[0].title").value("Java 后端工程师"));
    }

    @Test
    @DisplayName("GET /api/jobs: overseas 分栏参数透传到检索（v1.38.0 海外远程 Tab）")
    void list_passesOverseasFlag() throws Exception {
        when(jobAgentService.search(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/jobs").param("overseas", "true"))
                .andExpect(status().isOk());

        // 参数名/顺序写错会让「海外远程」分栏静默返回国内岗位，这里锁住透传
        verify(jobAgentService).search(any(), any(), any(), any(), any(), any(), any(), any(),
                eq(Boolean.TRUE), anyInt(), anyInt());
    }

    @Test
    @DisplayName("GET /api/jobs: 含注入特征的关键词在入口即被拒（P2-04）")
    void list_rejectsInjectionKeyword() throws Exception {
        // 线上形状：' OR 1=1 被 Render 边缘 WAF 以 403 + HTML 拦截页拒绝，且响应**不带 CORS 头**，
        // 浏览器只能上报 net::ERR_FAILED —— 前端拿不到任何可解释信息，曾据此误报「后端冷启动」。
        // 现改为在应用入口显式拒绝并说明原因，请求根本不会打到网关。
        mockMvc.perform(get("/api/jobs").param("keyword", "' OR 1=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("不支持的字符")));

        verify(jobAgentService, never()).search(any(), any(), any(), any(), any(), any(), any(),
                any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("GET /api/jobs: 超长关键词被拒（避免超长查询串触发网关规则）")
    void list_rejectsTooLongKeyword() throws Exception {
        mockMvc.perform(get("/api/jobs").param("keyword", "a".repeat(51)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("过长")));
    }

    @Test
    @DisplayName("GET /api/jobs: C++ / C# / node.js 等真实技术关键词不被白名单误伤")
    void list_allowsCommonTechSymbols() throws Exception {
        when(jobAgentService.search(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of()));

        for (String kw : List.of("C++", "C#", "node.js", "Java/Python", "前端 开发", "Vue3.0")) {
            mockMvc.perform(get("/api/jobs").param("keyword", kw))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }
    }

    @Test
    @DisplayName("GET /api/jobs/{id}: 岗位存在返回 200")
    void detail_found_returns200() throws Exception {
        JobPostingEntity job = JobPostingEntity.builder()
                .id(1L).title("Java 后端工程师").companyName("阿里巴巴").active(true).build();
        when(jobAgentService.findById(1L)).thenReturn(job);

        mockMvc.perform(get("/api/jobs/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.title").value("Java 后端工程师"));
    }

    @Test
    @DisplayName("GET /api/jobs/{id}: 岗位不存在或已下架返回 404 业务码")
    void detail_notFound_returns404() throws Exception {
        when(jobAgentService.findById(999L)).thenReturn(null);

        mockMvc.perform(get("/api/jobs/999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("岗位不存在或已下架"));
    }

    @Test
    @DisplayName("GET /api/jobs/meta: 返回筛选面板元数据")
    void meta_returnsAggregates() throws Exception {
        when(jobAgentService.meta(any())).thenReturn(Map.of(
                "industries", List.of("互联网", "金融"),
                "recruitCounts", Map.of("AUTUMN", 5)));

        mockMvc.perform(get("/api/jobs/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.industries.length()").value(2))
                .andExpect(jsonPath("$.data.recruitCounts.AUTUMN").value(5));
    }

    @Test
    @DisplayName("GET /api/jobs/meta: overseas 透传到服务层（P2-08 分栏计数口径）")
    void meta_passesOverseasFlag() throws Exception {
        when(jobAgentService.meta(any())).thenReturn(Map.of("recruitCounts", Map.of()));

        mockMvc.perform(get("/api/jobs/meta").param("overseas", "false"))
                .andExpect(status().isOk());

        // 「全部国内」分栏必须传 false；漏传会让 chips 退回全局数字（P2-08 的原始缺陷）
        verify(jobAgentService).meta(eq(Boolean.FALSE));
    }

    @Test
    @DisplayName("POST /api/jobs/match: 简历为空返回 400")
    void resumeMatch_blankResume_returns400() throws Exception {
        mockMvc.perform(post("/api/jobs/match")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("resumeText", "   "))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请提供简历内容"));
    }

    @Test
    @DisplayName("POST /api/jobs/match: 返回匹配岗位与技能画像")
    void resumeMatch_valid_returnsMatches() throws Exception {
        JobPostingEntity job = JobPostingEntity.builder()
                .id(1L).title("Java 后端工程师").companyName("阿里巴巴").active(true).build();
        when(jobAgentService.activeJobs()).thenReturn(List.of(job));
        when(jobMatchService.match(anyString(), anyList(), anyInt()))
                .thenReturn(List.of(new JobMatchService.MatchResult(job, 85, List.of("java"))));
        when(jobMatchService.extractSkills("熟悉 Java 与 Spring"))
                .thenReturn(Set.of("java", "spring"));

        mockMvc.perform(post("/api/jobs/match")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("resumeText", "熟悉 Java 与 Spring", "limit", "5"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.topSkills.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].matchScore").value(85))
                .andExpect(jsonPath("$.data.items[0].matchedSkills[0]").value("java"))
                .andExpect(jsonPath("$.data.items[0].job.title").value("Java 后端工程师"));
    }

    @Test
    @DisplayName("POST /api/jobs/refresh: 正常刷新返回统计结果")
    void refresh_success_returnsStats() throws Exception {
        when(jobAgentService.refresh())
                .thenReturn(new JobAgentService.RefreshResult(2, 1, 1, 3, 1));

        mockMvc.perform(post("/api/jobs/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.upserted").value(2))
                .andExpect(jsonPath("$.data.inserted").value(1))
                .andExpect(jsonPath("$.data.updated").value(1))
                .andExpect(jsonPath("$.data.expired").value(3))
                .andExpect(jsonPath("$.data.removed").value(1));
    }

    @Test
    @DisplayName("POST /api/jobs/refresh: 同一用户 5 分钟内重复触发被限流（429）")
    void refresh_userThrottled_returns429() throws Exception {
        // 直接消耗当前用户配额（窗口内 1 次）
        PerUserRateLimiter limiter = (PerUserRateLimiter)
                ReflectionTestUtils.getField(controller, "refreshLimiter");
        limiter.allow(USER_ID);

        mockMvc.perform(post("/api/jobs/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.message").value("刷新过于频繁，请 5 分钟后再试"));
        verify(jobAgentService, never()).refresh();
    }

    @Test
    @DisplayName("POST /api/jobs/refresh: 已有刷新任务执行中返回 429")
    void refresh_busy_returns429() throws Exception {
        when(jobAgentService.refresh()).thenReturn(null);

        mockMvc.perform(post("/api/jobs/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(429))
                .andExpect(jsonPath("$.message").value("岗位数据正在刷新中，请稍后再试"));
    }

    @Test
    @DisplayName("POST /api/jobs/refresh: 管理员旁路用户限流（配额耗尽仍可刷新）")
    void refresh_adminBypassesLimiter() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        // 消耗普通用户配额，验证管理员不查限流器
        PerUserRateLimiter limiter = (PerUserRateLimiter)
                ReflectionTestUtils.getField(controller, "refreshLimiter");
        limiter.allow("someone-else");
        limiter.allow(USER_ID);
        when(jobAgentService.refresh())
                .thenReturn(new JobAgentService.RefreshResult(1, 1, 0, 0, 0));

        mockMvc.perform(post("/api/jobs/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.upserted").value(1));
    }

    @Test
    @DisplayName("GET /favorite: 未认证用户返回 500（IllegalStateException 全局处理）")
    void favoriteList_unauthenticated_returns500() throws Exception {
        SecurityContextHolder.clearContext();

        mockMvc.perform(get("/api/jobs/favorite"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("服务器内部错误，请稍后重试"));
    }

    @Test
    @DisplayName("POST /favorite/toggle: jobId 非数字返回 400")
    void favoriteToggle_badJobIdFormat_returns400() throws Exception {
        mockMvc.perform(post("/api/jobs/favorite/toggle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("jobId", "abc"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("jobId 格式不正确"));
    }
}
