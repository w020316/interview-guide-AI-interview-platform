package com.example.interview.controller;

import com.example.interview.entity.JobPostingEntity;
import com.example.interview.repository.InterviewQuestionRepository;
import com.example.interview.repository.InterviewSessionRepository;
import com.example.interview.repository.ResumeRepository;
import com.example.interview.security.JwtUtil;
import com.example.interview.service.JobFavoriteService;
import com.example.interview.service.job.JobAgentService;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link JobAgentController} 岗位收藏端点 MockMvc 测试
 *
 * <p>覆盖 /api/jobs/favorite、/api/jobs/favorite/ids、/api/jobs/favorite/toggle。
 * Controller 直接注入 Repository（列表/详情等既有端点路径），故 @MockBean 三个
 * 统计相关 Repository 满足上下文依赖；收藏行为通过 @MockBean JobFavoriteService 隔离。
 */
@WebMvcTest(controllers = JobAgentController.class,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration")
class JobFavoriteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JobAgentService jobAgentService;

    @MockBean
    private JobFavoriteService jobFavoriteService;

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
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("GET /favorite: 返回收藏列表，7 天内截止附 remind 标记")
    void favoriteList_shouldIncludeRemindFlag() throws Exception {
        var fav = com.example.interview.entity.JobFavoriteEntity.builder()
                .id(1L).userId(USER_ID).jobId(100L)
                .title("Java 后端工程师").companyName("示例科技")
                .deadline(LocalDate.now().plusDays(3))
                .applyUrl("https://example.com/apply")
                .build();
        when(jobFavoriteService.listByUser(USER_ID)).thenReturn(List.of(fav));

        mockMvc.perform(get("/api/jobs/favorite"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].jobId").value(100))
                .andExpect(jsonPath("$.data.items[0].daysLeft").value(3))
                .andExpect(jsonPath("$.data.items[0].remind").value(true))
                .andExpect(jsonPath("$.data.items[0].expired").value(false));
    }

    @Test
    @DisplayName("GET /favorite: 已过期岗位附 expired 标记且不提醒")
    void favoriteList_expired_shouldNotRemind() throws Exception {
        var fav = com.example.interview.entity.JobFavoriteEntity.builder()
                .id(2L).userId(USER_ID).jobId(101L)
                .title("前端工程师").companyName("某公司")
                .deadline(LocalDate.now().minusDays(2))
                .build();
        when(jobFavoriteService.listByUser(USER_ID)).thenReturn(List.of(fav));

        mockMvc.perform(get("/api/jobs/favorite"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].expired").value(true))
                .andExpect(jsonPath("$.data.items[0].remind").value(false));
    }

    @Test
    @DisplayName("GET /favorite/ids: 返回已收藏岗位 ID 集合")
    void favoriteIds_shouldReturnSet() throws Exception {
        when(jobFavoriteService.listFavoriteJobIds(USER_ID)).thenReturn(Set.of(100L, 101L));

        mockMvc.perform(get("/api/jobs/favorite/ids"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    @DisplayName("POST /favorite/toggle: 正常切换返回 favorited 状态")
    void favoriteToggle_shouldReturnFavorited() throws Exception {
        JobPostingEntity job = JobPostingEntity.builder()
                .id(100L).title("Java 后端工程师").companyName("示例科技").build();
        when(jobAgentService.findById(100L)).thenReturn(job);
        when(jobFavoriteService.toggle(eq(USER_ID), any(JobPostingEntity.class))).thenReturn(true);
        when(jobFavoriteService.countByUser(USER_ID)).thenReturn(1L);

        mockMvc.perform(post("/api/jobs/favorite/toggle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("jobId", 100))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.favorited").value(true))
                .andExpect(jsonPath("$.data.count").value(1));
    }

    @Test
    @DisplayName("POST /favorite/toggle: jobId 缺失返回 400")
    void favoriteToggle_missingJobId_returns400() throws Exception {
        mockMvc.perform(post("/api/jobs/favorite/toggle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("POST /favorite/toggle: 岗位不存在返回 404")
    void favoriteToggle_jobNotFound_returns404() throws Exception {
        when(jobAgentService.findById(999L)).thenReturn(null);

        mockMvc.perform(post("/api/jobs/favorite/toggle")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("jobId", 999))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }
}
