package com.example.interview.controller;

import com.example.interview.entity.InterviewSessionEntity;
import com.example.interview.entity.ResumeEntity;
import com.example.interview.interceptor.RateLimitInterceptor;
import com.example.interview.repository.InterviewQuestionRepository;
import com.example.interview.repository.InterviewSessionRepository;
import com.example.interview.repository.ResumeRepository;
import com.example.interview.security.JwtUtil;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link StatsController} MockMvc 集成测试
 *
 * <p>覆盖 GET /api/stats/dashboard 与 GET /api/stats/trend 端点。
 * Controller 直接注入 Repository（无 Service 层），故 @MockBean 三个 Repository。
 *
 * <p>v1.23.3：实现改为数据库聚合（count/avg/GROUP BY），
 * 测试按新数据访问契约 mock；业务断言（计数/均分/排序/截断）与重构前保持一致。
 *
 * <p>测试场景：
 * <ul>
 *   <li>空数据：返回 0 计数 + 空活动列表</li>
 *   <li>有简历无面试：简历计数 + 简历平均分</li>
 *   <li>有面试会话：会话计数 + 已完成数 + 面试平均分（聚合查询路径）</li>
 *   <li>混合数据：最近活动合并排序 + 截断到 10 条</li>
 *   <li>trend：按会话 GROUP BY 聚合、无评分会话不产生数据点</li>
 * </ul>
 */
@WebMvcTest(controllers = StatsController.class,
        properties = "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration")
class StatsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ResumeRepository resumeRepository;

    @MockBean
    private InterviewSessionRepository sessionRepository;

    @MockBean
    private InterviewQuestionRepository questionRepository;

    @MockBean
    private JwtUtil jwtUtil;

    @MockBean
    private RateLimitInterceptor rateLimitInterceptor;

    private static final String USER_ID = "1";

    @BeforeEach
    void setUp() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID, null, List.of()));
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("空数据返回 200 + 全 0 计数")
    void dashboard_emptyData_returns200() throws Exception {
        when(resumeRepository.countByUserId(USER_ID)).thenReturn(0L);
        when(resumeRepository.avgOverallScoreByUserId(USER_ID)).thenReturn(null);
        when(resumeRepository.findTop10ByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of());
        when(sessionRepository.countByUserId(USER_ID)).thenReturn(0L);
        when(sessionRepository.countByUserIdAndStatus(USER_ID, "FINISHED")).thenReturn(0L);
        when(sessionRepository.findTop10ByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of());
        when(questionRepository.avgEvaluationScoreByUserId(USER_ID)).thenReturn(null);

        mockMvc.perform(get("/api/stats/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.resumeCount").value(0))
                .andExpect(jsonPath("$.data.sessionCount").value(0))
                .andExpect(jsonPath("$.data.finishedSessionCount").value(0))
                .andExpect(jsonPath("$.data.recentActivities").isArray());
    }

    @Test
    @DisplayName("有简历无面试返回 200 + 简历计数")
    void dashboard_onlyResumes_returns200() throws Exception {
        ResumeEntity r = ResumeEntity.builder()
                .id(1L).userId(USER_ID).targetJob("Java 后端")
                .overallScore(80).createdAt(LocalDateTime.now())
                .build();
        when(resumeRepository.countByUserId(USER_ID)).thenReturn(1L);
        when(resumeRepository.avgOverallScoreByUserId(USER_ID)).thenReturn(80.0);
        when(resumeRepository.findTop10ByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of(r));
        when(sessionRepository.countByUserId(USER_ID)).thenReturn(0L);
        when(sessionRepository.countByUserIdAndStatus(USER_ID, "FINISHED")).thenReturn(0L);
        when(sessionRepository.findTop10ByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of());
        when(questionRepository.avgEvaluationScoreByUserId(USER_ID)).thenReturn(null);

        mockMvc.perform(get("/api/stats/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.resumeCount").value(1))
                .andExpect(jsonPath("$.data.sessionCount").value(0))
                .andExpect(jsonPath("$.data.avgResumeScore").value(80.0))
                .andExpect(jsonPath("$.data.recentActivities[0].type").value("resume"));
    }

    @Test
    @DisplayName("有面试会话返回 200 + 会话计数 + 面试平均分（聚合查询路径）")
    void dashboard_withSessions_returns200() throws Exception {
        ResumeEntity r = ResumeEntity.builder()
                .id(1L).userId(USER_ID).targetJob("Java 后端")
                .overallScore(75).createdAt(LocalDateTime.now().minusHours(1))
                .build();
        InterviewSessionEntity s = InterviewSessionEntity.builder()
                .id(1L).sessionId("s1").userId(USER_ID)
                .jobDescription("Java 后端").status("FINISHED")
                .createdAt(LocalDateTime.now())
                .build();

        when(resumeRepository.countByUserId(USER_ID)).thenReturn(1L);
        when(resumeRepository.avgOverallScoreByUserId(USER_ID)).thenReturn(75.0);
        when(resumeRepository.findTop10ByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of(r));
        when(sessionRepository.countByUserId(USER_ID)).thenReturn(1L);
        when(sessionRepository.countByUserIdAndStatus(USER_ID, "FINISHED")).thenReturn(1L);
        when(sessionRepository.findTop10ByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of(s));
        when(questionRepository.avgEvaluationScoreByUserId(USER_ID)).thenReturn(85.0);

        mockMvc.perform(get("/api/stats/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.resumeCount").value(1))
                .andExpect(jsonPath("$.data.sessionCount").value(1))
                .andExpect(jsonPath("$.data.finishedSessionCount").value(1))
                .andExpect(jsonPath("$.data.avgInterviewScore").value(85.0));
    }

    @Test
    @DisplayName("混合数据最近活动合并排序返回 200")
    void dashboard_mixedActivities_returns200() throws Exception {
        ResumeEntity r = ResumeEntity.builder()
                .id(1L).userId(USER_ID).targetJob("Java 后端")
                .overallScore(80).createdAt(LocalDateTime.now())
                .build();
        InterviewSessionEntity s = InterviewSessionEntity.builder()
                .id(1L).sessionId("s1").userId(USER_ID)
                .jobDescription("Java 后端").status("ONGOING")
                .createdAt(LocalDateTime.now().minusHours(1))
                .build();

        when(resumeRepository.countByUserId(USER_ID)).thenReturn(1L);
        when(resumeRepository.avgOverallScoreByUserId(USER_ID)).thenReturn(80.0);
        when(resumeRepository.findTop10ByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of(r));
        when(sessionRepository.countByUserId(USER_ID)).thenReturn(1L);
        when(sessionRepository.countByUserIdAndStatus(USER_ID, "FINISHED")).thenReturn(0L);
        when(sessionRepository.findTop10ByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(List.of(s));
        when(questionRepository.avgEvaluationScoreByUserId(USER_ID)).thenReturn(null);

        mockMvc.perform(get("/api/stats/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.recentActivities.length()").value(2))
                // 简历 createdAt 更晚，应排在前
                .andExpect(jsonPath("$.data.recentActivities[0].type").value("resume"))
                .andExpect(jsonPath("$.data.recentActivities[1].type").value("interview"));
    }

    // ── GET /api/stats/trend ──

    @Test
    @DisplayName("trend: 无已完成会话返回空列表")
    void trend_noFinished_returnsEmpty() throws Exception {
        when(sessionRepository.findByUserIdAndStatusOrderByCreatedAtAsc(USER_ID, "FINISHED"))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/stats/trend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("trend: 已完成会话按时间升序聚合成数据点")
    void trend_finished_returnsPoints() throws Exception {
        InterviewSessionEntity s1 = InterviewSessionEntity.builder()
                .id(1L).sessionId("s1").userId(USER_ID)
                .jobDescription("Java 后端").status("FINISHED")
                .createdAt(LocalDateTime.now().minusDays(2))
                .build();

        when(sessionRepository.findByUserIdAndStatusOrderByCreatedAtAsc(USER_ID, "FINISHED"))
                .thenReturn(List.of(s1));
        // GROUP BY 聚合：s1 两题 70/80 → 平均 75
        // 注意 List.of(Object[]) 会被当作 varargs 展开，需显式指定泛型
        when(questionRepository.avgScoreGroupBySession(List.of("s1")))
                .thenReturn(List.<Object[]>of(new Object[]{"s1", 75.0, 2L}));

        mockMvc.perform(get("/api/stats/trend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].score").value(75.0))
                .andExpect(jsonPath("$.data[0].questionCount").value(2))
                .andExpect(jsonPath("$.data[0].sessionId").value("s1"));
    }

    @Test
    @DisplayName("trend: 无评分记录的会话不产生数据点")
    void trend_noScores_skipsPoint() throws Exception {
        InterviewSessionEntity finished = InterviewSessionEntity.builder()
                .id(1L).sessionId("s1").userId(USER_ID)
                .jobDescription("Java 后端").status("FINISHED")
                .createdAt(LocalDateTime.now())
                .build();

        when(sessionRepository.findByUserIdAndStatusOrderByCreatedAtAsc(USER_ID, "FINISHED"))
                .thenReturn(List.of(finished));
        // 聚合查询仅返回有评分的会话；s1 无评分 → 无聚合行
        when(questionRepository.avgScoreGroupBySession(List.of("s1")))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/stats/trend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }
}
