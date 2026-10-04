package com.example.interview.service.job;

import com.example.interview.entity.InterviewEventEntity;
import com.example.interview.entity.JobApplicationEntity;
import com.example.interview.service.InterviewEventService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@link ApplicationTimelineService} 单元测试（v1.61.0 投递 ↔ 面试时序视图）
 *
 * <p>本类的守卫重点<b>不是「功能能跑」</b>，而是三条产品红线：
 * <ol>
 *   <li>没有真实投出时间的草稿不得出现在时间线上（不拿 createdAt 冒充）；</li>
 *   <li>日历事件只按 {@code applicationId} 关联，<b>绝不按标题模糊匹配</b>
 *       （重名岗位会产生假关联）；</li>
 *   <li>样本为空时统计值必须是 {@code null}，<b>不产出假 0</b>。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ApplicationTimelineService 投递↔面试时序单元测试")
class ApplicationTimelineServiceTest {

    @Mock
    private JobApplicationService applicationService;

    @Mock
    private InterviewEventService eventService;

    @InjectMocks
    private ApplicationTimelineService service;

    private static final String USER = "user-1";

    private JobApplicationEntity app(Long id, String status, LocalDateTime appliedAt) {
        return JobApplicationEntity.builder()
                .id(id).userId(USER).jobId(id)
                .title("Java 后端").companyName("腾讯").platform("BOSS直聘")
                .applyUrl("https://example.com/apply")
                .status(status)
                .appliedAt(appliedAt)
                .build();
    }

    private InterviewEventEntity event(Long id, Long applicationId, LocalDateTime at, String title) {
        return InterviewEventEntity.builder()
                .id(id).userId(USER).title(title)
                .applicationId(applicationId)
                .interviewAt(at)
                .status("UPCOMING")
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> nodes(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("nodes");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> stats(Map<String, Object> result) {
        return (Map<String, Object>) result.get("stats");
    }

    // ───────── 红线 1：没有投出时间的草稿不进时间线 ─────────

    @Test
    @DisplayName("PLANNED 草稿（appliedAt 为空）不产生时间线节点——不拿 createdAt 冒充投出时间")
    void plannedDraftIsNotOnTimeline() {
        when(applicationService.listByUser(USER)).thenReturn(List.of(
                app(1L, JobApplicationEntity.STATUS_PLANNED, null)));
        when(eventService.listByUser(USER)).thenReturn(List.of());

        Map<String, Object> result = service.timeline(USER);

        assertThat(nodes(result)).isEmpty();
        assertThat(stats(result).get("appliedCount")).isEqualTo(0L);
    }

    @Test
    @DisplayName("已投递记录产生 APPLIED 节点，携带公司的岗位快照与渠道")
    void appliedRecordProducesNode() {
        when(applicationService.listByUser(USER)).thenReturn(List.of(
                app(1L, JobApplicationEntity.STATUS_APPLIED, LocalDateTime.of(2026, 10, 1, 10, 0))));
        when(eventService.listByUser(USER)).thenReturn(List.of());

        Map<String, Object> result = service.timeline(USER);
        List<Map<String, Object>> nodes = nodes(result);

        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).get("kind")).isEqualTo(ApplicationTimelineService.KIND_APPLIED);
        assertThat(nodes.get(0).get("at")).isEqualTo("2026-10-01T10:00");
        assertThat(nodes.get(0).get("companyName")).isEqualTo("腾讯");
        assertThat(nodes.get(0).get("stageLabel")).isEqualTo("已投递");
        assertThat(nodes.get(0).get("channel")).isEqualTo("BOSS直聘");
        assertThat(stats(result).get("appliedCount")).isEqualTo(1L);
    }

    // ───────── 红线 2：只按 applicationId 关联，不按标题模糊匹配 ─────────

    @Test
    @DisplayName("日历事件只按 applicationId 关联——标题相同但无外键时如实标为未关联")
    void eventWithSameTitleButNoLinkStaysUnlinked() {
        // 投递记录标题与日历事件标题完全一致，但事件没带 applicationId。
        // 若实现用「标题包含」做关联，这里就会误判成已关联 —— 本用例正是为此守卫。
        when(applicationService.listByUser(USER)).thenReturn(List.of(
                app(1L, JobApplicationEntity.STATUS_APPLIED, LocalDateTime.of(2026, 10, 1, 10, 0))));
        when(eventService.listByUser(USER)).thenReturn(List.of(
                event(9L, null, LocalDateTime.of(2026, 10, 5, 14, 0), "Java 后端")));

        Map<String, Object> result = service.timeline(USER);
        List<Map<String, Object>> interviewNodes = nodes(result).stream()
                .filter(n -> ApplicationTimelineService.KIND_INTERVIEW.equals(n.get("kind")))
                .toList();

        assertThat(interviewNodes).hasSize(1);
        assertThat(interviewNodes.get(0).get("applicationId")).isNull();
        assertThat(interviewNodes.get(0).get("companyName")).isNull();
        // 未关联 → 不产出「投递到面试几天」这个派生值
        assertThat(interviewNodes.get(0).get("daysSinceApplied")).isNull();
        assertThat(stats(result).get("unlinkedInterviewCount")).isEqualTo(1);
        assertThat(stats(result).get("linkedInterviewCount")).isEqualTo(0);
    }

    @Test
    @DisplayName("applicationId 指向已移除的投递记录（悬空引用）不报错，按未关联处理")
    void danglingLinkIsTreatedAsUnlinked() {
        when(applicationService.listByUser(USER)).thenReturn(List.of());
        when(eventService.listByUser(USER)).thenReturn(List.of(
                event(9L, 999L, LocalDateTime.of(2026, 10, 5, 14, 0), "某公司 · 一面")));

        Map<String, Object> result = service.timeline(USER);
        List<Map<String, Object>> interviewNodes = nodes(result);

        assertThat(interviewNodes).hasSize(1);
        assertThat(interviewNodes.get(0).get("applicationId")).isNull();
        // 无关联时回退展示事件自身标题，而不是显示空白
        assertThat(interviewNodes.get(0).get("title")).isEqualTo("某公司 · 一面");
        assertThat(stats(result).get("unlinkedInterviewCount")).isEqualTo(1);
    }

    @Test
    @DisplayName("关联成功时计算「投出到面试」天数，并计入样本")
    void linkedEventComputesDaysSinceApplied() {
        when(applicationService.listByUser(USER)).thenReturn(List.of(
                app(1L, JobApplicationEntity.STATUS_INTERVIEW, LocalDateTime.of(2026, 10, 1, 10, 0))));
        when(eventService.listByUser(USER)).thenReturn(List.of(
                event(9L, 1L, LocalDateTime.of(2026, 10, 5, 14, 0), "腾讯 · 一面")));

        Map<String, Object> result = service.timeline(USER);
        List<Map<String, Object>> interviewNodes = nodes(result).stream()
                .filter(n -> ApplicationTimelineService.KIND_INTERVIEW.equals(n.get("kind")))
                .toList();

        assertThat(interviewNodes.get(0).get("applicationId")).isEqualTo(1L);
        assertThat(interviewNodes.get(0).get("companyName")).isEqualTo("腾讯");
        // 10-01 10:00 → 10-05 14:00 = 4 天 4 小时 = 4.2 天（精确到分钟再折算，不是粗暴减日期）
        assertThat(interviewNodes.get(0).get("daysSinceApplied")).isEqualTo(4.2);
        assertThat(stats(result).get("interviewSampleSize")).isEqualTo(1);
        assertThat(stats(result).get("avgDaysToInterview")).isEqualTo(4.2);
    }

    @Test
    @DisplayName("同日投递当天面试：算出 0.x 天而非 0（不能丢掉时分差）")
    void sameDayInterviewKeepsFraction() {
        when(applicationService.listByUser(USER)).thenReturn(List.of(
                app(1L, JobApplicationEntity.STATUS_INTERVIEW, LocalDateTime.of(2026, 10, 1, 8, 0))));
        when(eventService.listByUser(USER)).thenReturn(List.of(
                event(9L, 1L, LocalDateTime.of(2026, 10, 1, 14, 0), "腾讯 · 一面")));

        Map<String, Object> result = service.timeline(USER);
        List<Map<String, Object>> interviewNodes = nodes(result).stream()
                .filter(n -> ApplicationTimelineService.KIND_INTERVIEW.equals(n.get("kind")))
                .toList();

        // 同一天上午投、下午面 = 6 小时 = 0.3 天；若用 toEpochDay 相减会得到 0（错）
        assertThat(interviewNodes.get(0).get("daysSinceApplied")).isEqualTo(0.3);
    }

    // ───────── 红线 3：无样本不产出假 0 ─────────

    @Test
    @DisplayName("没有任何关联样本时，平均/中位数为 null 而非 0（无数据 ≠ 0）")
    void noSamplesMeansNullNotZero() {
        when(applicationService.listByUser(USER)).thenReturn(List.of(
                app(1L, JobApplicationEntity.STATUS_APPLIED, LocalDateTime.of(2026, 10, 1, 10, 0))));
        when(eventService.listByUser(USER)).thenReturn(List.of());

        Map<String, Object> result = service.timeline(USER);

        assertThat(stats(result).get("interviewSampleSize")).isEqualTo(0);
        assertThat(stats(result).get("avgDaysToInterview")).isNull();
        assertThat(stats(result).get("medianDaysToInterview")).isNull();
    }

    @Test
    @DisplayName("完全无数据时返回空时间线与全 null 统计，不抛异常")
    void emptyEverythingIsSafe() {
        when(applicationService.listByUser(USER)).thenReturn(List.of());
        when(eventService.listByUser(USER)).thenReturn(List.of());

        Map<String, Object> result = service.timeline(USER);

        assertThat(nodes(result)).isEmpty();
        assertThat(stats(result).get("appliedCount")).isEqualTo(0L);
        assertThat(stats(result).get("interviewCount")).isEqualTo(0);
        assertThat(stats(result).get("avgDaysToInterview")).isNull();
    }

    // ───────── 排序与统计 ─────────

    @Test
    @DisplayName("节点按时间升序排列；中位数在多样本时取中间值")
    void nodesAreSortedAndMedianIsCorrect() {
        when(applicationService.listByUser(USER)).thenReturn(List.of(
                app(1L, JobApplicationEntity.STATUS_INTERVIEW, LocalDateTime.of(2026, 10, 1, 10, 0)),
                app(2L, JobApplicationEntity.STATUS_INTERVIEW, LocalDateTime.of(2026, 10, 1, 10, 0)),
                app(3L, JobApplicationEntity.STATUS_INTERVIEW, LocalDateTime.of(2026, 10, 1, 10, 0))));
        when(eventService.listByUser(USER)).thenReturn(List.of(
                event(9L, 1L, LocalDateTime.of(2026, 10, 3, 10, 0), "A"),   // 2 天
                event(10L, 2L, LocalDateTime.of(2026, 10, 5, 10, 0), "B"),  // 4 天
                event(11L, 3L, LocalDateTime.of(2026, 10, 11, 10, 0), "C"))); // 10 天

        Map<String, Object> result = service.timeline(USER);
        List<Map<String, Object>> nodes = nodes(result);

        // 时间升序：3 个投递节点（10-01）在最前，随后是三场面试
        assertThat(nodes.get(0).get("at")).isEqualTo("2026-10-01T10:00");
        assertThat(nodes.get(nodes.size() - 1).get("at")).isEqualTo("2026-10-11T10:00");

        assertThat(stats(result).get("interviewSampleSize")).isEqualTo(3);
        assertThat(stats(result).get("avgDaysToInterview")).isEqualTo(5.3);   // (2+4+10)/3 = 5.33
        assertThat(stats(result).get("medianDaysToInterview")).isEqualTo(4.0);
    }

    @Test
    @DisplayName("天数折算：跨日含时分差，四舍五入到 1 位小数")
    void daysBetweenRoundsToTenth() {
        assertThat(ApplicationTimelineService.daysBetween(
                LocalDateTime.of(2026, 10, 1, 10, 0),
                LocalDateTime.of(2026, 10, 5, 14, 0))).isEqualTo(4.2);
        assertThat(ApplicationTimelineService.daysBetween(
                LocalDateTime.of(2026, 10, 1, 0, 0),
                LocalDateTime.of(2026, 10, 2, 0, 0))).isEqualTo(1.0);
    }
}
