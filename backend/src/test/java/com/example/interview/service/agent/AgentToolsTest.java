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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
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

    @Mock
    private com.example.interview.service.InterviewService interviewService;

    private AgentTools newTools() {
        return new AgentTools("user-1", jobAgentService, interviewSessionService,
                interviewEventService, ragSearchService, webJobSearcherService,
                jobMatchService, interviewService);
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

    @Test
    @DisplayName("generateInterviewQuestions：正常解析并列出题目")
    void genQuestionsOk() {
        when(interviewService.generateQuestions(anyString(), anyString(), anyString(), anyInt(), anyString(), anyString()))
                .thenReturn("[{\"question\":\"介绍项目架构\",\"category\":\"项目深挖\",\"difficulty\":\"MEDIUM\"}]");
        String out = newTools().generateInterviewQuestions(1, "Java后端", "MEDIUM");
        assertThat(out).contains("已为你生成").contains("Java后端").contains("介绍项目架构");
    }

    @Test
    @DisplayName("generateInterviewQuestions：count 越界收敛到 1-10")
    void genQuestionsClamp() {
        when(interviewService.generateQuestions(anyString(), anyString(), anyString(), eq(10), anyString(), anyString()))
                .thenReturn("[]");
        newTools().generateInterviewQuestions(99, null, null);
        org.mockito.Mockito.verify(interviewService).generateQuestions(anyString(), anyString(), anyString(), eq(10), anyString(), anyString());
    }

    @Test
    @DisplayName("generateInterviewQuestions：异常时返回降级文案")
    void genQuestionsError() {
        when(interviewService.generateQuestions(anyString(), anyString(), anyString(), anyInt(), anyString(), anyString()))
                .thenThrow(new RuntimeException("ai down"));
        assertThat(newTools().generateInterviewQuestions(5, null, null)).contains("出题暂时不可用");
    }

    // ───────── 出题带用户简历上下文（v1.34.1 P3-11 修复回归）─────────
    // 背景：generateInterviewQuestions 此前把 resumeText 传空串，出题丢失用户简历上下文，
    // 生成的是通用题而非针对该用户经历/技术栈的题。

    /** 构造带指定简历服务的工具实例 */
    private AgentTools toolsWithResume(com.example.interview.service.ResumeService rs) {
        return new AgentTools("user-1", jobAgentService, interviewSessionService,
                interviewEventService, ragSearchService, webJobSearcherService,
                jobMatchService, interviewService, rs);
    }

    @Test
    @DisplayName("generateInterviewQuestions：把用户最近简历传给出题服务（P3-11 回归）")
    void genQuestionsPassesLatestResume() {
        com.example.interview.service.ResumeService resumeService =
                org.mockito.Mockito.mock(com.example.interview.service.ResumeService.class);
        var resume = com.example.interview.entity.ResumeEntity.builder()
                .content("三年 Java 经验，主导订单中心重构，QPS 峰值 3000")
                .build();
        when(resumeService.listByUser("user-1")).thenReturn(List.of(resume));
        when(interviewService.generateQuestions(anyString(), anyString(), anyString(), anyInt(), anyString(), anyString()))
                .thenReturn("[{\"question\":\"介绍订单中心重构\",\"category\":\"项目深挖\",\"difficulty\":\"MEDIUM\"}]");

        String out = toolsWithResume(resumeService).generateInterviewQuestions(1, "Java后端", "MEDIUM");

        assertThat(out).contains("介绍订单中心重构");
        // 关键断言：简历文本确实被传给出题服务（修复前恒为空串）
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(interviewService).generateQuestions(anyString(), captor.capture(), anyString(), anyInt(), anyString(), anyString());
        assertThat(captor.getValue()).contains("主导订单中心重构");
    }

    @Test
    @DisplayName("generateInterviewQuestions：用户无简历时降级为通用出题（传空串，不报错）")
    void genQuestionsWithoutResumeFallsBack() {
        com.example.interview.service.ResumeService resumeService =
                org.mockito.Mockito.mock(com.example.interview.service.ResumeService.class);
        when(resumeService.listByUser("user-1")).thenReturn(List.of());
        when(interviewService.generateQuestions(anyString(), anyString(), anyString(), anyInt(), anyString(), anyString()))
                .thenReturn("[]");

        toolsWithResume(resumeService).generateInterviewQuestions(1, "Java后端", null);

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(interviewService).generateQuestions(anyString(), captor.capture(), anyString(), anyInt(), anyString(), anyString());
        assertThat(captor.getValue()).isEmpty();
    }

    @Test
    @DisplayName("generateInterviewQuestions：简历服务抛异常时降级为通用出题，不中断工作流")
    void genQuestionsResumeServiceErrorDegrades() {
        com.example.interview.service.ResumeService resumeService =
                org.mockito.Mockito.mock(com.example.interview.service.ResumeService.class);
        when(resumeService.listByUser("user-1")).thenThrow(new RuntimeException("db down"));
        when(interviewService.generateQuestions(anyString(), anyString(), anyString(), anyInt(), anyString(), anyString()))
                .thenReturn("[]");

        // 不得抛出，且仍完成出题调用
        toolsWithResume(resumeService).generateInterviewQuestions(1, "Java后端", null);

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(interviewService).generateQuestions(anyString(), captor.capture(), anyString(), anyInt(), anyString(), anyString());
        assertThat(captor.getValue()).isEmpty();
    }

    @Test
    @DisplayName("deepFollowUp：正常生成追问")
    void deepFollowUpOk() {
        when(interviewService.generateFollowUp(anyString(), anyString(), anyString()))
                .thenReturn("这个场景下你是如何保证 Redis 与 DB 一致性的？");
        String out = newTools().deepFollowUp("讲讲缓存策略", "我用了先更新 DB 再删缓存", "");;
        assertThat(out).contains("深挖追问").contains("Redis");
    }

    @Test
    @DisplayName("deepFollowUp：缺 question 或 userAnswer 时提示")
    void deepFollowUpMissing() {
        assertThat(newTools().deepFollowUp("", "我的回答", "")).contains("请提供面试题与你的作答内容");
        assertThat(newTools().deepFollowUp("问题", "  ", "")).contains("请提供面试题与你的作答内容");
    }

    @Test
    @DisplayName("deepFollowUp：异常时返回降级文案")
    void deepFollowUpError() {
        when(interviewService.generateFollowUp(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("ai down"));
        assertThat(newTools().deepFollowUp("问题", "回答", "")).contains("追问生成暂时不可用");
    }

    // ───────────────────── dispatch：工具协议统一入口 ─────────────────────

    @Test
    @DisplayName("dispatch：未知工具返回错误提示并列出可用工具")
    void dispatchUnknownTool() {
        String out = newTools().dispatch("noSuchTool", "{}");
        assertThat(out).contains("未知工具 noSuchTool").contains("searchJobs").contains("searchWebJobs");
    }

    @Test
    @DisplayName("dispatch：params 为 null/空/非法 JSON 时按空参数处理")
    void dispatchBlankOrInvalidParams() {
        // null → searchKnowledge(null) → 拦截文案
        assertThat(newTools().dispatch("searchKnowledge", null)).contains("不能为空");
        // 非法 JSON → 解析失败按空参数 → 同样拦截
        assertThat(newTools().dispatch("searchKnowledge", "not-json{")).contains("不能为空");
        // 非 object JSON（数组）→ 空参数
        assertThat(newTools().dispatch("searchKnowledge", "[1,2]")).contains("不能为空");
    }

    @Test
    @DisplayName("dispatch：intVal 非数字参数回退 null（limit 用默认值）")
    void dispatchNonNumericIntParam() {
        when(interviewSessionService.listWrongQuestionsByUser(anyString(), anyInt())).thenReturn(List.of());
        String out = newTools().dispatch("listWrongQuestions", "{\"limit\":\"abc\"}");
        assertThat(out).contains("暂无错题");
    }

    @Test
    @DisplayName("all()：注册 9 个工具且关键工具在册")
    void registryContainsAllTools() {
        var all = newTools().all();
        assertThat(all).hasSize(9);
        assertThat(all).containsKeys("searchJobs", "searchWebJobs", "matchResumeJobs",
                "generateInterviewQuestions", "deepFollowUp", "searchKnowledge",
                "getMyInterviewStats", "listWrongQuestions", "getUpcomingInterviews");
    }

    // ───────────────────── searchJobs：字段格式化与参数归一 ─────────────────────

    @Test
    @DisplayName("searchJobs：recruitType 归一为大写，缺省时为 AUTUMN")
    void searchJobs_recruitTypeNormalized() {
        when(jobAgentService.search(any(), any(), any(), any(), anyString(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of()));
        newTools().searchJobs(null, null, null, null, "spring");
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jobAgentService).search(isNull(), isNull(), isNull(), isNull(),
                captor.capture(), isNull(), isNull(), isNull(), eq(0), eq(8));
        assertThat(captor.getValue()).isEqualTo("SPRING");

        // 字符串 "null"（模型常见输出）→ AUTUMN
        newTools().searchJobs(null, null, null, null, "null");
        verify(jobAgentService, org.mockito.Mockito.times(2))
                .search(isNull(), isNull(), isNull(), isNull(), captor.capture(), isNull(), isNull(), isNull(), eq(0), eq(8));
        assertThat(captor.getAllValues()).contains("AUTUMN");
    }

    @Test
    @DisplayName("searchJobs：空字段回退默认文案（地点未标注/面议/不限，无截止与链接）")
    void searchJobs_nullFieldsFormatting() {
        var job = com.example.interview.entity.JobPostingEntity.builder()
                .title("算法工程师").companyName("某公司").build();
        when(jobAgentService.search(any(), any(), any(), any(), anyString(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(job)));

        String out = newTools().searchJobs(null, null, null, null, null);
        assertThat(out).contains("算法工程师").contains("某公司")
                .contains("地点未标注").contains("面议").contains("学历:不限")
                .doesNotContain("截止:").doesNotContain("申请:");
    }

    // ───────────────────── searchWebJobs：字段回退与异常降级 ─────────────────────

    @Test
    @DisplayName("searchWebJobs：抓取异常（非空结果路径之外的失败）降级到本地库")
    void searchWebJobs_searchWebThrows_fallsBackToLocal() {
        when(webJobSearcherService.searchWeb(anyString(), any())).thenThrow(new RuntimeException("network down"));
        when(jobAgentService.search(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of()));
        assertThat(newTools().searchWebJobs("Java", "深圳")).contains("均未找到匹配岗位");
    }

    @Test
    @DisplayName("searchWebJobs：联网结果空字段回退默认文案，学历/申请链接非空时追加")
    void searchWebJobs_blankFieldFallbacks() {
        var web = new com.example.interview.service.job.WebJobSearcherService.WebJob(
                "Go 工程师", null, "", "", "本科", "", "https://zhipin.com/1");
        when(webJobSearcherService.searchWeb(anyString(), any())).thenReturn(List.of(web));

        String out = newTools().searchWebJobs("Go", null);
        assertThat(out).contains("Go 工程师").contains("企业待确认").contains("地点待确认")
                .contains("面议").contains("学历:本科").contains("申请:https://zhipin.com/1");
    }

    @Test
    @DisplayName("searchWebJobs：关键词为 null 或“null”回退默认 Java")
    void searchWebJobs_blankKeywordDefaultsToJava() {
        when(webJobSearcherService.searchWeb(org.mockito.ArgumentMatchers.eq("Java"), any()))
                .thenReturn(List.of());
        when(jobAgentService.search(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of()));
        newTools().searchWebJobs(null, null);
        newTools().searchWebJobs("null", "  ");
        verify(webJobSearcherService, org.mockito.Mockito.times(2)).searchWeb(eq("Java"), any());
    }

    @Test
    @DisplayName("searchWebJobs：联网降级时本地库检索也异常→双降级文案")
    void searchWebJobs_fallbackLocalThrows() {
        when(webJobSearcherService.searchWeb(anyString(), any())).thenReturn(List.of());
        when(jobAgentService.search(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("db down"));
        assertThat(newTools().searchWebJobs("Java", null)).contains("联网搜索与本地岗位库暂时都不可用");
    }

    @Test
    @DisplayName("searchWebJobs：本地降级岗位缺申请链接时不输出“申请:”")
    void searchWebJobs_fallbackNoApplyUrl() {
        when(webJobSearcherService.searchWeb(anyString(), any())).thenReturn(List.of());
        var job = com.example.interview.entity.JobPostingEntity.builder()
                .title("Java 后端").companyName("腾讯").location("深圳").salary("25k").build();
        when(jobAgentService.search(any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(job)));
        String out = newTools().searchWebJobs("Java", null);
        assertThat(out).contains("本地岗位库推荐").doesNotContain("申请:");
    }

    // ───────────────────── matchResumeJobs：参数与异常 ─────────────────────

    @Test
    @DisplayName("matchResumeJobs：字符串“null”视为空简历")
    void matchResumeJobs_nullString() {
        assertThat(newTools().matchResumeJobs("null")).contains("请提供简历核心内容");
    }

    @Test
    @DisplayName("matchResumeJobs：匹配服务异常时返回降级文案")
    void matchResumeJobs_error() {
        when(jobAgentService.activeJobs()).thenReturn(List.of());
        when(jobMatchService.match(anyString(), any(), anyInt())).thenThrow(new RuntimeException("boom"));
        assertThat(newTools().matchResumeJobs("熟悉Java")).contains("简历匹配暂时不可用");
    }

    @Test
    @DisplayName("matchResumeJobs：岗位缺地点/薪资/链接时回退默认文案")
    void matchResumeJobs_nullFieldFallbacks() {
        var job = com.example.interview.entity.JobPostingEntity.builder()
                .title("Java 后端").companyName("某公司").build();
        when(jobAgentService.activeJobs()).thenReturn(List.of(job));
        when(jobMatchService.match(anyString(), any(), anyInt())).thenReturn(List.of(
                new com.example.interview.service.job.JobMatchService.MatchResult(job, 60, List.of("java"))));
        String out = newTools().matchResumeJobs("熟悉Java");
        assertThat(out).contains("60 分").contains("地点未标注").contains("面议").doesNotContain("申请:");
    }

    // ───────────────────── generateInterviewQuestions：解析边界 ─────────────────────

    @Test
    @DisplayName("generateInterviewQuestions：count 缺省为 5、非法难度回退空串")
    void genQuestions_defaultCountAndInvalidDifficulty() {
        when(interviewService.generateQuestions(anyString(), anyString(), anyString(), anyInt(), anyString(), anyString()))
                .thenReturn("[]");
        newTools().generateInterviewQuestions(null, null, "IMPOSSIBLE");
        verify(interviewService).generateQuestions(anyString(), anyString(), eq("通用技术岗"), eq(5), eq(""), eq(""));
    }

    @Test
    @DisplayName("generateInterviewQuestions：AI 返回空内容时提示出题失败")
    void genQuestions_blankJson() {
        when(interviewService.generateQuestions(anyString(), anyString(), anyString(), anyInt(), anyString(), anyString()))
                .thenReturn("  ");
        assertThat(newTools().generateInterviewQuestions(3, null, null)).contains("出题失败");
    }

    @Test
    @DisplayName("generateInterviewQuestions：非数组 JSON 原样截断展示")
    void genQuestions_nonArrayJson() {
        String json = "{\"pad\":\"" + "x".repeat(400) + "\"}";
        when(interviewService.generateQuestions(anyString(), anyString(), anyString(), anyInt(), anyString(), anyString()))
                .thenReturn(json);
        String out = newTools().generateInterviewQuestions(3, null, null);
        assertThat(out).contains("x".repeat(200)).doesNotContain("x".repeat(293));
    }

    @Test
    @DisplayName("generateInterviewQuestions：题目字段全空时提示未解析到题目")
    void genQuestions_allBlankQuestions() {
        when(interviewService.generateQuestions(anyString(), anyString(), anyString(), anyInt(), anyString(), anyString()))
                .thenReturn("[{\"question\":\"\"},{\"question\":\"   \"}]");
        assertThat(newTools().generateInterviewQuestions(3, null, null)).contains("未解析到题目");
    }

    // ───────────────────── deepFollowUp：空响应与 resume 缺省 ─────────────────────

    @Test
    @DisplayName("deepFollowUp：追问为空时提示失败；resumeText 缺省传空串")
    void deepFollowUp_blankResponse() {
        when(interviewService.generateFollowUp(eq("问题"), eq("回答"), eq("")))
                .thenReturn("  ");
        assertThat(newTools().deepFollowUp("问题", "回答", "null")).contains("追问生成失败");
    }

    // ───────────────────── searchKnowledge：空结果与异常 ─────────────────────

    @Test
    @DisplayName("searchKnowledge：空结果与异常分别回退默认文案")
    void searchKnowledge_emptyAndError() {
        when(ragSearchService.search(eq("无结果主题"), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.eq("user-1")))
                .thenReturn("");
        assertThat(newTools().searchKnowledge("无结果主题")).contains("知识库中未找到相关内容");

        when(ragSearchService.search(eq("异常主题"), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.eq("user-1")))
                .thenThrow(new RuntimeException("rag down"));
        assertThat(newTools().searchKnowledge("异常主题")).contains("知识库检索暂时不可用");
    }

    // ───────────────────── getMyInterviewStats：降级与类型分支 ─────────────────────

    @Test
    @DisplayName("getMyInterviewStats：统计服务异常时返回降级文案")
    void statsError() {
        when(interviewSessionService.questionSummary(anyString())).thenThrow(new RuntimeException("db down"));
        assertThat(newTools().getMyInterviewStats()).contains("面试统计暂时不可用");
    }

    @Test
    @DisplayName("getMyInterviewStats：平均分为非 Double 类型时原样输出，空分类列表跳过")
    void stats_integerAvgAndEmptyCategories() {
        when(interviewSessionService.questionSummary("user-1")).thenReturn(Map.of(
                "totalQuestions", 3L, "averageScore", 70, "byCategory", List.of()));
        String out = newTools().getMyInterviewStats();
        assertThat(out).contains("总题数:3").contains("平均分:70").doesNotContain("分类掌握度:");
    }

    // ───────────────────── listWrongQuestions：格式化与降级 ─────────────────────

    @Test
    @DisplayName("listWrongQuestions：limit 上界收敛、空分类/难度回退、长题干截断")
    void wrongQuestions_formattingAndClamp() {
        var q = InterviewQuestionEntity.builder()
                .question("这是一个非常长的面试题目内容用来验证题干截断逻辑。".repeat(3))
                .evaluationScore(55).build();
        when(interviewSessionService.listWrongQuestionsByUser(anyString(), anyInt())).thenReturn(List.of(q));

        String out = newTools().listWrongQuestions(20);
        assertThat(out).contains("未分类").contains("MEDIUM").contains("得分55").contains("...");
    }

    @Test
    @DisplayName("listWrongQuestions：查询异常时返回降级文案")
    void wrongQuestions_error() {
        when(interviewSessionService.listWrongQuestionsByUser(anyString(), anyInt()))
                .thenThrow(new RuntimeException("db down"));
        assertThat(newTools().listWrongQuestions(null)).contains("错题查询暂时不可用");
    }

    // ───────────────────── getUpcomingInterviews：空值与降级 ─────────────────────

    @Test
    @DisplayName("getUpcomingInterviews：时间为空/状态为空回退默认值")
    void calendar_nullFieldFallbacks() {
        var e = InterviewEventEntity.builder().title("字节二面").build();
        when(interviewEventService.listByUser(anyString())).thenReturn(List.of(e));
        String out = newTools().getUpcomingInterviews();
        assertThat(out).contains("字节二面").contains("时间待定").contains("UPCOMING");
    }

    @Test
    @DisplayName("getUpcomingInterviews：查询异常时返回降级文案")
    void calendar_error() {
        when(interviewEventService.listByUser(anyString())).thenThrow(new RuntimeException("db down"));
        assertThat(newTools().getUpcomingInterviews()).contains("面试日历查询暂时不可用");
    }

    // ───────────────────── dispatch：经注册表执行各工具（lambda 协议路径） ─────────────────────

    @Test
    @DisplayName("dispatch searchWebJobs：经注册表执行联网搜索工具")
    void dispatch_searchWebJobs() {
        var web = new com.example.interview.service.job.WebJobSearcherService.WebJob(
                "Java 开发", "某公司", "深圳", "20k", "", "", "https://sou.zhaopin.com");
        when(webJobSearcherService.searchWeb(anyString(), any())).thenReturn(List.of(web));

        String out = newTools().dispatch("searchWebJobs", "{\"keyword\":\"Java\",\"location\":\"深圳\"}");
        assertThat(out).contains("联网实时搜索到").contains("Java 开发");
    }

    @Test
    @DisplayName("dispatch matchResumeJobs：经注册表执行简历匹配工具")
    void dispatch_matchResumeJobs() {
        var job = com.example.interview.entity.JobPostingEntity.builder()
                .title("Java 后端").companyName("某公司").build();
        when(jobAgentService.activeJobs()).thenReturn(List.of(job));
        when(jobMatchService.match(anyString(), any(), anyInt())).thenReturn(List.of(
                new com.example.interview.service.job.JobMatchService.MatchResult(job, 75, List.of("java"))));

        String out = newTools().dispatch("matchResumeJobs", "{\"resumeText\":\"熟悉Java\"}");
        assertThat(out).contains("75 分").contains("Java 后端");
    }

    @Test
    @DisplayName("dispatch generateInterviewQuestions：参数经 parseParams 解析（count/direction/difficulty）")
    void dispatch_generateInterviewQuestions() {
        when(interviewService.generateQuestions(anyString(), anyString(), anyString(), anyInt(), anyString(), anyString()))
                .thenReturn("[{\"question\":\"介绍项目\",\"category\":\"项目深挖\",\"difficulty\":\"EASY\"}]");

        String out = newTools().dispatch("generateInterviewQuestions",
                "{\"count\":\"2\",\"direction\":\"Java后端\",\"difficulty\":\"EASY\"}");
        assertThat(out).contains("已为你生成 2 道模拟面试题（方向：Java后端）").contains("介绍项目");
    }

    @Test
    @DisplayName("dispatch generateInterviewQuestions：空参数时 count 走默认 5（intVal null 分支）")
    void dispatch_generateInterviewQuestions_emptyParams_intValNull() {
        when(interviewService.generateQuestions(anyString(), anyString(), anyString(), anyInt(), anyString(), anyString()))
                .thenReturn("[]");

        newTools().dispatch("generateInterviewQuestions", "{}");
        verify(interviewService).generateQuestions(anyString(), anyString(), eq("通用技术岗"), eq(5), eq(""), eq(""));
    }

    @Test
    @DisplayName("dispatch deepFollowUp：经注册表执行深挖追问工具")
    void dispatch_deepFollowUp() {
        when(interviewService.generateFollowUp(anyString(), anyString(), anyString()))
                .thenReturn("追问：如何保证缓存一致性？");

        String out = newTools().dispatch("deepFollowUp",
                "{\"question\":\"缓存策略\",\"userAnswer\":\"先更新DB再删缓存\",\"resumeText\":\"熟悉Redis\"}");
        assertThat(out).contains("深挖追问").contains("缓存一致性");
    }

    @Test
    @DisplayName("dispatch listWrongQuestions：经注册表执行错题查询工具（limit 参数解析）")
    void dispatch_listWrongQuestions() {
        var q = InterviewQuestionEntity.builder()
                .question("HashMap 原理").category("Java基础").difficulty("EASY").evaluationScore(40).build();
        when(interviewSessionService.listWrongQuestionsByUser(anyString(), anyInt())).thenReturn(List.of(q, q));

        String out = newTools().dispatch("listWrongQuestions", "{\"limit\":2}");
        assertThat(out).contains("最近 2 道").contains("HashMap");
    }

    @Test
    @DisplayName("dispatch getUpcomingInterviews：经注册表执行面试日历工具")
    void dispatch_getUpcomingInterviews() {
        var e = InterviewEventEntity.builder().title("腾讯一面").status("UPCOMING").build();
        when(interviewEventService.listByUser(anyString())).thenReturn(List.of(e));

        String out = newTools().dispatch("getUpcomingInterviews", "{}");
        assertThat(out).contains("面试安排共 1 条").contains("腾讯一面");
    }
}
