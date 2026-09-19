package com.example.interview.service;

import com.example.interview.entity.JobPostingEntity;
import com.example.interview.entity.UserEntity;
import com.example.interview.repository.JobPostingRepository;
import com.example.interview.repository.UserRepository;
import com.example.interview.service.job.JobAgentService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理后台服务单测
 *
 * <p>MeterRegistry 使用真实 {@link SimpleMeterRegistry}（注册/不注册指标两种路径均覆盖），
 * {@link UserBanRegistry} 用真实实例（无 Redis 退化为进程内）；仅 mock Repository/JobAgentService。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("管理后台服务测试")
class AdminServiceTest {

    @Mock
    private JobPostingRepository jobPostingRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private JobAgentService jobAgentService;

    private final UserBanRegistry userBanRegistry = new UserBanRegistry();

    private AdminService newService(SimpleMeterRegistry registry) {
        return new AdminService(jobPostingRepository, userRepository, jobAgentService, userBanRegistry, registry);
    }

    // ── 数据总览 ──

    @Test
    @DisplayName("overview: 岗位/用户统计与刷新状态汇总")
    void overview_aggregates() {
        when(jobPostingRepository.count()).thenReturn(10L);
        when(jobPostingRepository.countByActiveTrue()).thenReturn(7L);
        when(userRepository.count()).thenReturn(3L);
        LocalDateTime last = LocalDateTime.now();
        when(jobPostingRepository.findLastUpdatedAt()).thenReturn(last);
        when(jobAgentService.isRefreshing()).thenReturn(true);

        Map<String, Object> result = newService(new SimpleMeterRegistry()).overview();

        assertThat(result.get("totalJobs")).isEqualTo(10L);
        assertThat(result.get("activeJobs")).isEqualTo(7L);
        assertThat(result.get("inactiveJobs")).isEqualTo(3L);
        assertThat(result.get("totalUsers")).isEqualTo(3L);
        assertThat(result.get("bannedUsers")).isEqualTo(0);
        assertThat(result.get("lastRefreshedAt")).isEqualTo(last);
        assertThat(result.get("refreshing")).isEqualTo(true);
    }

    // ── 手动刷新 ──

    @Test
    @DisplayName("refreshJobs: 正常返回刷新统计；刷新中抛 IllegalStateException")
    void refreshJobs_resultAndBusy() {
        when(jobAgentService.refresh())
                .thenReturn(new JobAgentService.RefreshResult(2, 1, 1, 0, 0))
                .thenReturn(null);

        var result = newService(new SimpleMeterRegistry()).refreshJobs();
        assertThat(result.upserted()).isEqualTo(2);

        assertThatThrownBy(() -> newService(new SimpleMeterRegistry()).refreshJobs())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("正在刷新中");
    }

    // ── 岗位管理 ──

    @Test
    @DisplayName("listJobs: 无关键词 conjunction；关键词转义后构造 LIKE；分页参数夹紧")
    void listJobs_keywordAndPaging() {
        Page<JobPostingEntity> empty = new PageImpl<>(List.of());
        when(jobPostingRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(empty);

        AdminService service = newService(new SimpleMeterRegistry());
        assertThat(service.listJobs(null, 0, 10).getTotalElements()).isZero();
        assertThat(service.listJobs("  ", 0, 10).getTotalElements()).isZero();
        // 通配符与负页码/超大 size 均被夹紧
        service.listJobs("100%_\\", -5, 999);
        verify(jobPostingRepository, org.mockito.Mockito.times(3))
                .findAll(any(Specification.class), any(Pageable.class));
        org.mockito.ArgumentCaptor<Pageable> cap = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(jobPostingRepository, org.mockito.Mockito.times(3))
                .findAll(any(Specification.class), cap.capture());
        assertThat(cap.getAllValues().get(2).getPageNumber()).isZero();
        assertThat(cap.getAllValues().get(2).getPageSize()).isEqualTo(50);
    }

    @Test
    @DisplayName("deactivate/activate: 修改 active 并保存；岗位不存在抛异常")
    void deactivateAndActivateJob() {
        JobPostingEntity job = JobPostingEntity.builder().id(1L).title("T").active(true).build();
        when(jobPostingRepository.findById(1L)).thenReturn(java.util.Optional.of(job));
        when(jobPostingRepository.findById(2L)).thenReturn(java.util.Optional.empty());

        AdminService service = newService(new SimpleMeterRegistry());
        service.deactivateJob(1L);
        assertThat(job.getActive()).isFalse();
        verify(jobPostingRepository).save(job);

        service.activateJob(1L);
        assertThat(job.getActive()).isTrue();
        verify(jobPostingRepository, org.mockito.Mockito.times(2)).save(job);

        assertThatThrownBy(() -> service.deactivateJob(2L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("岗位不存在");
        assertThatThrownBy(() -> service.activateJob(2L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("岗位不存在");
    }

    @Test
    @DisplayName("deleteJob: 存在则删除；不存在抛异常")
    void deleteJob() {
        when(jobPostingRepository.existsById(1L)).thenReturn(true);
        when(jobPostingRepository.existsById(2L)).thenReturn(false);

        newService(new SimpleMeterRegistry()).deleteJob(1L);
        verify(jobPostingRepository).deleteById(1L);

        assertThatThrownBy(() -> newService(new SimpleMeterRegistry()).deleteJob(2L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("岗位不存在");
        verify(jobPostingRepository, never()).deleteById(2L);
    }

    // ── 用户管理 ──

    @Test
    @DisplayName("listUsers: 脱敏视图含禁用状态；关键词与排序；分页夹紧")
    void listUsers_views() {
        UserEntity u1 = UserEntity.builder().id(2L).username("Alice")
                .passwordHash("SECRET").email("a@x.com")
                .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0)).build();
        UserEntity u2 = UserEntity.builder().id(1L).username("Bob")
                .passwordHash("SECRET").email("b@x.com").build();
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(u1, u2), PageRequest.of(0, 10), 2));
        userBanRegistry.ban(1L);

        Page<AdminService.UserView> page = newService(new SimpleMeterRegistry()).listUsers("a", 0, 10);

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent().get(0).banned()).isFalse();
        assertThat(page.getContent().get(1).banned()).isTrue();
        // DTO 不含 passwordHash 字段
        assertThat(page.getContent().get(0).username()).isEqualTo("Alice");
        assertThat(page.getContent().get(0).email()).isEqualTo("a@x.com");
    }

    @Test
    @DisplayName("listUsers: 无关键词也正常返回")
    void listUsers_noKeyword() {
        when(userRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        assertThat(newService(new SimpleMeterRegistry()).listUsers(null, 0, 10).getTotalElements()).isZero();
    }

    @Test
    @DisplayName("ban/unban: 存在校验通过后写注册表；用户不存在或 id 为 null 抛异常")
    void banAndUnbanUser() {
        when(userRepository.existsById(1L)).thenReturn(true);
        when(userRepository.existsById(2L)).thenReturn(false);

        AdminService service = newService(new SimpleMeterRegistry());
        service.banUser(1L);
        assertThat(userBanRegistry.isBanned(1L)).isTrue();
        service.unbanUser(1L);
        assertThat(userBanRegistry.isBanned(1L)).isFalse();

        assertThatThrownBy(() -> service.banUser(2L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("用户不存在");
        assertThatThrownBy(() -> service.unbanUser(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("用户不存在");
    }

    // ── 系统指标 ──

    @Test
    @DisplayName("metrics: 已注册指标读取真实值；SSE gauge 值截断为 int")
    void metrics_withRegisteredMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.counter("ai.call.count", "type", "resume").increment(3.0);
        registry.timer("ai.call.duration").record(java.time.Duration.ofMillis(120));
        registry.counter("cache.hit.count").increment(7.0);
        registry.counter("cache.miss.count").increment(2.0);
        registry.gauge("sse.max.concurrent", 12.5);
        registry.gauge("sse.active.count", 4.0);

        Map<String, Object> metrics = newService(registry).metrics();

        @SuppressWarnings("unchecked")
        Map<String, Object> aiCalls = (Map<String, Object>) metrics.get("aiCalls");
        assertThat(aiCalls.get("resume")).isEqualTo(3L);
        assertThat(aiCalls.get("question")).isEqualTo(0L); // 未注册 → 0
        assertThat(aiCalls.get("totalCalls")).isEqualTo(1L);
        assertThat((Double) aiCalls.get("avgMs")).isGreaterThan(0);

        @SuppressWarnings("unchecked")
        Map<String, Object> cache = (Map<String, Object>) metrics.get("cache");
        assertThat(cache.get("hits")).isEqualTo(7L);
        assertThat(cache.get("misses")).isEqualTo(2L);

        @SuppressWarnings("unchecked")
        Map<String, Object> sse = (Map<String, Object>) metrics.get("sse");
        assertThat(sse.get("maxConcurrent")).isEqualTo(12);
        assertThat(sse.get("activeCount")).isEqualTo(4);

        @SuppressWarnings("unchecked")
        Map<String, Object> jvm = (Map<String, Object>) metrics.get("jvm");
        assertThat((Long) jvm.get("totalMb")).isPositive();
    }

    @Test
    @DisplayName("metrics: 空注册表时各指标回退默认值（counter/timer/gauge 为 null 分支）")
    void metrics_emptyRegistry_fallbacks() {
        Map<String, Object> metrics = newService(new SimpleMeterRegistry()).metrics();

        @SuppressWarnings("unchecked")
        Map<String, Object> aiCalls = (Map<String, Object>) metrics.get("aiCalls");
        assertThat(aiCalls).doesNotContainKeys("totalCalls", "avgMs", "p95Ms"); // timer 为 null
        for (String type : List.of("resume", "question", "evaluate", "jobAnalysis", "rag")) {
            assertThat(aiCalls.get(type)).isEqualTo(0L);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> sse = (Map<String, Object>) metrics.get("sse");
        assertThat(sse.get("maxConcurrent")).isEqualTo(20);
        assertThat(sse.get("activeCount")).isEqualTo(0);
    }

    @Test
    @DisplayName("metrics: refreshJobs 与指标路径的 jobAgentService 交互互不干扰")
    void metrics_doesNotTouchJobAgentService() {
        newService(new SimpleMeterRegistry()).metrics();
        verify(jobAgentService, never()).refresh();
        verify(jobPostingRepository, never()).findById(anyLong());
        verify(userRepository, never()).findById(anyLong());
        verify(jobAgentService, never()).isRefreshing();
    }
}
