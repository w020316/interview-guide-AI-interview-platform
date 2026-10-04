package com.example.interview.service;

import com.example.interview.entity.InterviewEventEntity;
import com.example.interview.entity.JobApplicationEntity;
import com.example.interview.entity.JobFavoriteEntity;
import com.example.interview.service.job.JobApplicationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TodoService 单元测试（第三批 B · 零 AI 待办聚合）")
class TodoServiceTest {

    @Mock private JobApplicationService applicationService;
    @Mock private InterviewEventService eventService;
    @Mock private JobFavoriteService favoriteService;
    @InjectMocks private TodoService service;

    @SuppressWarnings("unchecked")
    private Map<String, Object> group(Map<String, Object> result, String key) {
        List<Map<String, Object>> groups = (List<Map<String, Object>>) result.get("groups");
        return groups.stream().filter(g -> key.equals(g.get("key"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> failures(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("partialFailures");
    }

    @Test
    @DisplayName("全空：四组 count=0（available=true），无 partialFailures（空态由前端呈现）")
    void today_allEmpty_countsZero() {
        when(applicationService.listByUser("u1")).thenReturn(List.of());
        when(eventService.listByUser("u1")).thenReturn(List.of());
        when(favoriteService.listByUser("u1")).thenReturn(List.of());

        Map<String, Object> result = service.today("u1", 3);

        assertThat(result.get("generatedAt")).isNotNull();
        assertThat((List<?>) result.get("partialFailures")).isEmpty();
        for (String key : List.of("FOLLOW_UP", "UPCOMING", "DEADLINE", "PLANNED")) {
            Map<String, Object> g = group(result, key);
            assertThat(g.get("count")).isEqualTo(0);
            assertThat(g.get("available")).isEqualTo(true);
            assertThat((List<?>) g.get("items")).isEmpty();
        }
    }

    @Test
    @DisplayName("某组数据源异常 → 该组 count=null（不是 0）且计入 partialFailures，其余组照常")
    void today_sourceFailure_countIsNullNotZero() {
        when(applicationService.listByUser("u1")).thenThrow(new RuntimeException("db down"));
        when(eventService.listByUser("u1")).thenReturn(List.of());
        when(favoriteService.listByUser("u1")).thenReturn(List.of());

        Map<String, Object> result = service.today("u1", 3);

        // FOLLOW_UP 与 PLANNED 都来自 applicationService → 两组均降级
        for (String key : List.of("FOLLOW_UP", "PLANNED")) {
            Map<String, Object> g = group(result, key);
            assertThat(g.get("count")).as("源不可用必须是 null（区别于 0）").isNull();
            assertThat(g.get("available")).isEqualTo(false);
        }
        // 其余组照常
        assertThat(group(result, "UPCOMING").get("count")).isEqualTo(0);
        assertThat(group(result, "DEADLINE").get("count")).isEqualTo(0);
        assertThat(failures(result)).extracting(f -> f.get("group"))
                .containsExactlyInAnyOrder("FOLLOW_UP", "PLANNED");
    }

    @Test
    @DisplayName("待跟进：needsFollowUp=true 的投递进入 FOLLOW_UP，带 route 与 routeQuery")
    void today_followUpItems() {
        LocalDateTime now = LocalDateTime.now();
        JobApplicationEntity a = JobApplicationEntity.builder()
                .id(11L).userId("u1").jobId(1L)
                .companyName("字节").title("Java 后端").status("APPLIED")
                .appliedAt(now.minusDays(9)).build();
        when(applicationService.listByUser("u1")).thenReturn(List.of(a));
        when(applicationService.needsFollowUp(any(), any())).thenReturn(true);
        when(eventService.listByUser("u1")).thenReturn(List.of());
        when(favoriteService.listByUser("u1")).thenReturn(List.of());

        Map<String, Object> result = service.today("u1", 3);

        Map<String, Object> g = group(result, "FOLLOW_UP");
        assertThat(g.get("count")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) g.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("title")).isEqualTo("字节 · Java 后端");
        assertThat(items.get(0).get("route")).isEqualTo("/applications");
        assertThat(items.get(0).get("routeQuery")).isEqualTo(Map.of("focus", 11L));
        assertThat(items.get(0).get("reason").toString()).contains("无状态变化");
    }

    @Test
    @DisplayName("近期日程：窗口内 UPCOMING 进入 UPCOMING 组")
    void today_upcomingWithinWindow() {
        LocalDateTime now = LocalDateTime.now();
        InterviewEventEntity soon = InterviewEventEntity.builder()
                .id(21L).userId("u1").title("腾讯 · 一面")
                .interviewAt(now.plusDays(1)).status("UPCOMING").build();
        InterviewEventEntity done = InterviewEventEntity.builder()
                .id(22L).userId("u1").title("已完成").interviewAt(now.plusDays(1)).status("DONE").build();
        InterviewEventEntity far = InterviewEventEntity.builder()
                .id(23L).userId("u1").title("太远").interviewAt(now.plusDays(10)).status("UPCOMING").build();
        when(applicationService.listByUser("u1")).thenReturn(List.of());
        when(eventService.listByUser("u1")).thenReturn(List.of(soon, done, far));
        when(favoriteService.listByUser("u1")).thenReturn(List.of());

        Map<String, Object> g = group(service.today("u1", 3), "UPCOMING");

        assertThat(g.get("count")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) g.get("items");
        assertThat(items.get(0).get("title")).isEqualTo("腾讯 · 一面");
        assertThat(items.get(0).get("route")).isEqualTo("/calendar");
    }

    @Test
    @DisplayName("临近截止：窗口内收藏进入 DEADLINE 组")
    void today_deadlineWithinWindow() {
        JobFavoriteEntity fav = JobFavoriteEntity.builder()
                .id(31L).userId("u1").jobId(100L)
                .companyName("Shopee").title("数据分析")
                .deadline(LocalDate.now().plusDays(3)).build();
        JobFavoriteEntity expired = JobFavoriteEntity.builder()
                .id(32L).userId("u1").jobId(101L)
                .companyName("旧").title("过期").deadline(LocalDate.now().minusDays(1)).build();
        when(applicationService.listByUser("u1")).thenReturn(List.of());
        when(eventService.listByUser("u1")).thenReturn(List.of());
        when(favoriteService.listByUser("u1")).thenReturn(List.of(fav, expired));

        Map<String, Object> g = group(service.today("u1", 3), "DEADLINE");

        assertThat(g.get("count")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) g.get("items");
        assertThat(items.get(0).get("title")).isEqualTo("Shopee · 数据分析");
        assertThat(items.get(0).get("route")).isEqualTo("/jobs");
    }

    @Test
    @DisplayName("待投递：PLANNED 状态进入 PLANNED 组")
    void today_plannedItems() {
        JobApplicationEntity planned = JobApplicationEntity.builder()
                .id(41L).userId("u1").jobId(1L)
                .companyName("美团").title("后端").status("PLANNED").build();
        JobApplicationEntity applied = JobApplicationEntity.builder()
                .id(42L).userId("u1").jobId(2L)
                .companyName("已投").title("后端").status("APPLIED").build();
        lenient().when(applicationService.needsFollowUp(any(), any())).thenReturn(false);
        when(applicationService.listByUser("u1")).thenReturn(List.of(planned, applied));
        when(eventService.listByUser("u1")).thenReturn(List.of());
        when(favoriteService.listByUser("u1")).thenReturn(List.of());

        Map<String, Object> g = group(service.today("u1", 3), "PLANNED");

        assertThat(g.get("count")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) g.get("items");
        assertThat(items.get(0).get("title")).isEqualTo("美团 · 后端");
    }

    @Test
    @DisplayName("resolveWindow：非法/过小取默认 3，过大钳到 30")
    void resolveWindow_bounds() {
        assertThat(TodoService.resolveWindow(0)).isEqualTo(3);
        assertThat(TodoService.resolveWindow(-5)).isEqualTo(3);
        assertThat(TodoService.resolveWindow(5)).isEqualTo(5);
        assertThat(TodoService.resolveWindow(100)).isEqualTo(30);
        assertThat(TodoService.resolveWindow(3)).isEqualTo(3);
    }
}
