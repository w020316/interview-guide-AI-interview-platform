package com.example.interview.service.agent;

import com.example.interview.entity.InterviewEventEntity;
import com.example.interview.entity.InterviewQuestionEntity;
import com.example.interview.service.InterviewEventService;
import com.example.interview.service.InterviewSessionService;
import com.example.interview.service.RagSearchService;
import com.example.interview.service.job.JobAgentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 智能体工具层单元测试
 * 覆盖：正常裁剪、空数据边界、异常降级文案
 */
@ExtendWith(MockitoExtension.class)
class AgentToolsTest {

    @Mock
    private JobAgentService jobAgentService;
    @Mock
    private InterviewSessionService interviewSessionService;
    @Mock
    private InterviewEventService interviewEventService;
    @Mock
    private RagSearchService ragSearchService;
    @Mock
    private com.example.interview.service.job.WebJobSearcherService webJobSearcherService;

    @Mock
    private com.example.interview.service.job.JobMatchService jobMatchService;

    private AgentTools newTools() {
        return new AgentTools("user-1", jobAgentService, interviewSessionService,
                interviewEventService, ragSearchService, webJobSearcherService, jobMatchService);
    }

    @Test
    @DisplayName("searchJobs：正常返回岗位列表（含链接与截止日期）")
    void searchJobsOk() {
        var job = com.example.interview.entity.JobPostingEntity.builder()
                .title("Java 后端").companyName("腾讯").location("深圳")
                .salary("25k").degree("本科").deadline(LocalDate.of(2026, 10, 1))
                .applyUrl("https://join.qq.com").build();
        when(jobAgentService.search(any(), any(), any(), any(), anyString(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(job)));

        String out = newTools().searchJobs("java", null, null, null, null);
        assertThat(out).contains("Java 后端").contains("腾讯").contains("https://join.qq.com").contains("2026-10-01");
    }

    @Test
    @DisplayName("searchJobs：空结果返回引导文案")
    void searchJobsEmpty() {
        when(jobAgentService.search(any(), any(), any(), any(), anyString(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of()));
        String out = newTools().searchJobs("不存在的岗位", null, null, null, null);
        assertThat(out).contains("未找到匹配岗位");
    }

    @Test
    @DisplayName("searchJobs：异常时返回降级文案而非抛出")
    void searchJobsError() {
        when(jobAgentService.search(any(), any(), any(), any(), anyString(), any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("db down"));
        String out = newTools().searchJobs(null, null, null, null, null);
        assertThat(out).contains("暂时不可用");
    }

    @Test
    @DisplayName("searchKnowledge：空查询直接拦截")
    void searchKnowledgeBlank() {
        assertThat(newTools().searchKnowledge(" ")).contains("不能为空");
    }

    @Test
    @DisplayName("searchKnowledge：正常返回知识片段")
    void searchKnowledgeOk() {
        when(ragSearchService.search(anyString(), anyInt(), anyString())).thenReturn("HashMap 原理：数组+链表+红黑树");
        assertThat(newTools().searchKnowledge("HashMap")).contains("红黑树");
    }

    @Test
    @DisplayName("getMyInterviewStats：统计字段完整输出")
    void statsOk() {
        when(interviewSessionService.questionSummary("user-1")).thenReturn(Map.of(
                "totalQuestions", 10L, "answeredQuestions", 8L, "wrongQuestions", 2L,
                "averageScore", 72.5, "byCategory", List.of(Map.of("category", "Java基础", "avgScore", 70.0))));
        String out = newTools().getMyInterviewStats();
        assertThat(out).contains("总题数:10").contains("错题数:2").contains("平均分:72.5").contains("Java基础");
    }

    @Test
    @DisplayName("listWrongQuestions：无错题返回正向引导")
    void wrongEmpty() {
        when(interviewSessionService.listWrongQuestionsByUser(anyString(), anyInt())).thenReturn(List.of());
        assertThat(newTools().listWrongQuestions(null)).contains("暂无错题");
    }

    @Test
    @DisplayName("listWrongQuestions：limit 超界收敛到 8-15 区间")
    void wrongLimitClamp() {
        var q = InterviewQuestionEntity.builder().question("什么是 HashMap？")
                .category("Java基础").difficulty("EASY").evaluationScore(40).build();
        when(interviewSessionService.listWrongQuestionsByUser(anyString(), anyInt()))
                .thenReturn(List.of(q, q, q));
        // limit=-5 收敛为默认 8（实际只有 3 条）
        String out = newTools().listWrongQuestions(-5);
        assertThat(out).contains("最近 3 道").contains("HashMap");
    }

    @Test
    @DisplayName("getUpcomingInterviews：空日历引导添加")
    void calendarEmpty() {
        when(interviewEventService.listByUser(anyString())).thenReturn(List.of());
        assertThat(newTools().getUpcomingInterviews()).contains("面试日历为空");
    }

    @Test
    @DisplayName("getUpcomingInterviews：正常返回日程")
    void calendarOk() {
        var e = InterviewEventEntity.builder().title("腾讯一面")
                .status("UPCOMING").build();
        when(interviewEventService.listByUser(anyString())).thenReturn(List.of(e));
        assertThat(newTools().getUpcomingInterviews()).contains("腾讯一面").contains("UPCOMING");
    }

    @Test
    @DisplayName("searchWebJobs：联网抓取到真实岗位时返回联网结果")
    void searchWebJobsOk() {
        var web = new com.example.interview.service.job.WebJobSearcherService.WebJob(
                "Java开发工程师", "某互联网公司", "深圳", "20k-40k", "本科", "", "https://sou.zhaopin.com");
        when(webJobSearcherService.searchWeb(anyString(), any())).thenReturn(List.of(web));
        String out = newTools().searchWebJobs("Java", "深圳");
        assertThat(out).contains("联网实时搜索到").contains("Java开发工程师").contains("深圳");
    }

    @Test
    @DisplayName("searchWebJobs：联网无结果时降级到本地岗位库")
    void searchWebJobsFallback() {
        when(webJobSearcherService.searchWeb(anyString(), any())).thenReturn(List.of());
        var job = com.example.interview.entity.JobPostingEntity.builder()
                .title("Java 后端").companyName("腾讯").location("深圳").salary("25k")
                .applyUrl("https://join.qq.com").build();
        when(jobAgentService.search(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(job)));
        String out = newTools().searchWebJobs("Java", null);
        assertThat(out).contains("本地岗位库").contains("Java 后端");
    }

    @Test
    @DisplayName("searchWebJobs：联网与本地均无数据返回引导文案")
    void searchWebJobsBothEmpty() {
        when(webJobSearcherService.searchWeb(anyString(), any())).thenReturn(List.of());
        when(jobAgentService.search(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of()));
        String out = newTools().searchWebJobs("Java", "沈阳");
        assertThat(out).contains("搜索与本地岗位库均未找到");
    }

    @Test
    @DisplayName("工具结果超长时截断到上限")
    void truncateLimit() {
        StringBuilder longText = new StringBuilder("x".repeat(3000));
        var job = com.example.interview.entity.JobPostingEntity.builder()
                .title(longText.toString()).companyName("T").build();
        when(jobAgentService.search(any(), any(), any(), any(), anyString(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(job)));
        String out = newTools().searchJobs(null, null, null, null, null);
        assertThat(out.length()).isLessThan(1600);
        assertThat(out).contains("结果已截断");
    }

    @Test
    @DisplayName("matchResumeJobs：简历要点命中时推荐高吻合岗位")
    void matchResumeJobsOk() {
        var job = com.example.interview.entity.JobPostingEntity.builder()
                .title("Java 后端").companyName("字节跳动").location("北京")
                .salary("30k-50k").applyUrl("https://jobs.bytedance.com").build();
        when(jobAgentService.activeJobs()).thenReturn(List.of(job));
        when(jobMatchService.match(anyString(), any(), anyInt())).thenReturn(List.of(
                new com.example.interview.service.job.JobMatchService.MatchResult(job, 80, List.of("java", "spring"))));
        String out = newTools().matchResumeJobs("熟悉Java和Spring，本科");
        assertThat(out).contains("Java 后端").contains("80 分").contains("命中技能:java/spring");
    }

    @Test
    @DisplayName("matchResumeJobs：空简历给出提示")
    void matchResumeJobsBlank() {
        assertThat(newTools().matchResumeJobs("  ")).contains("请提供简历核心内容");
    }

    @Test
    @DisplayName("matchResumeJobs：无匹配岗位给出引导")
    void matchResumeJobsNoHit() {
        when(jobAgentService.activeJobs()).thenReturn(List.of());
        when(jobMatchService.match(anyString(), any(), anyInt())).thenReturn(List.of());
        String out = newTools().matchResumeJobs("熟悉Java，本科");
        assertThat(out).contains("暂未找到与这份简历吻合的岗位");
    }
}
