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
        AdminService service = new AdminService(jobPostingRepository, userRepository, jobAgentService,
                userBanRegistry, registry);
        // @Value 注入的字段在纯单测中不会被容器填充，显式设置以覆盖 P2-07 的「最后一个管理员」保护
        org.springframework.test.util.ReflectionTestUtils.setField(service, "adminUsernames", "小吴同学");
        return service;
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
        assertThat(service.listJobs(null, null, null, null, null, 0, 10).getTotalElements()).isZero();
        assertThat(service.listJobs("  ", null, null, null, null, 0, 10).getTotalElements()).isZero();
        // 通配符与负页码/超大 size 均被夹紧
        service.listJobs("100%_\\", null, null, null, null, -5, 999);
        verify(jobPostingRepository, org.mockito.Mockito.times(3))
                .findAll(any(Specification.class), any(Pageable.class));
        org.mockito.ArgumentCaptor<Pageable> cap = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(jobPostingRepository, org.mockito.Mockito.times(3))
                .findAll(any(Specification.class), cap.capture());
        assertThat(cap.getAllValues().get(2).getPageNumber()).isZero();
        assertThat(cap.getAllValues().get(2).getPageSize()).isEqualTo(50);
    }

    /**
     * v1.37.0 新增：来源 / 招聘类型 / 状态三个筛选条件。
     *
     * <p>Specification 是 lambda，其内部谓词无法直接断言（渲染谓词需要一整套
     * CriteriaBuilder mock，测试会变得比被测逻辑更脆）。这里只覆盖「参数可组合、
     * 不抛异常、仍落到同一次分页查询」——条件的实际 SQL 语义由 JobAgentService
     * 的查询路径与集成测试覆盖。
     */
    @Test
    @DisplayName("listJobs: 来源/类型/状态筛选可组合传入")
    void listJobs_acceptsOptionalFilters() {
        when(jobPostingRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        AdminService service = newService(new SimpleMeterRegistry());
        assertThat(service.listJobs(null, "行业精选", "AUTUMN", Boolean.TRUE, null, 0, 10).getTotalElements()).isZero();
        assertThat(service.listJobs("java", "行业精选", null, Boolean.FALSE, null, 2, 20).getTotalElements()).isZero();
        verify(jobPostingRepository, org.mockito.Mockito.times(2))
                .findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @DisplayName("listJobs: overseas 范围过滤复用适配器声明的海外源清单（P3-D）")
    void listJobs_overseasScopeFilter() {
        when(jobPostingRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(jobAgentService.overseasPlatforms()).thenReturn(List.of("remoteok", "remotive"));

        AdminService service = newService(new SimpleMeterRegistry());
        service.listJobs(null, null, null, null, Boolean.FALSE, 0, 10); // 仅国内
        service.listJobs(null, null, null, null, Boolean.TRUE, 0, 10);  // 仅海外
        service.listJobs(null, null, null, null, null, 0, 10);          // 不限：不查海外源清单

        // 只有显式传 overseas 时才需要海外源清单（null 不触发，避免无谓依赖）
        verify(jobAgentService, org.mockito.Mockito.times(2)).overseasPlatforms();
        verify(jobPostingRepository, org.mockito.Mockito.times(3))
                .findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    @DisplayName("overview: 附带数据源分布、招聘类型分布与近 7 天趋势")
    void overview_includesDistributionsAndTrend() {
        when(jobPostingRepository.count()).thenReturn(10L);
        when(jobPostingRepository.countByActiveTrue()).thenReturn(7L);
        when(userRepository.count()).thenReturn(3L);
        // 显式 List.<Object[]>of：否则泛型 + varargs 会让 javac 把 E 推断成 Object，
        // 与 thenReturn 期望的 List<Object[]> 不匹配（编译期报错）
        when(jobPostingRepository.countGroupByPlatform()).thenReturn(List.<Object[]>of(
                new Object[]{"秋招精选", 6L, 4L},
                new Object[]{"行业精选", 4L, 3L}));
        when(jobPostingRepository.countByRecruitType()).thenReturn(List.<Object[]>of(
                new Object[]{"AUTUMN", 6L},
                new Object[]{"SOCIAL", 4L}));
        LocalDateTime today = java.time.LocalDate.now().atTime(9, 0);
        when(jobPostingRepository.findCreatedAtSince(any())).thenReturn(List.of(today, today));
        when(userRepository.findCreatedAtSince(any())).thenReturn(List.of(today));

        Map<String, Object> result = newService(new SimpleMeterRegistry()).overview();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dist = (List<Map<String, Object>>) result.get("sourceDist");
        assertThat(dist).hasSize(2);
        // 失效数由 total - active 推导（用于发现「某个源集体过期」）
        assertThat(dist.get(0))
                .containsEntry("platform", "秋招精选")
                .containsEntry("total", 6L)
                .containsEntry("active", 4L)
                .containsEntry("inactive", 2L);

        assertThat(result.get("recruitDist")).isEqualTo(Map.of("AUTUMN", 6L, "SOCIAL", 4L));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> trend = (List<Map<String, Object>>) result.get("trend");
        assertThat(trend).hasSize(7);
        // 最后一天即今天，岗位 2 条、用户 1 名
        assertThat(trend.get(6))
                .containsEntry("date", java.time.LocalDate.now().toString())
                .containsEntry("jobs", 2L)
                .containsEntry("users", 1L);
        // 前 6 天无新增，返回 0 而不是缺失键（前端据此渲染 0 高度柱）
        assertThat(trend.get(0)).containsEntry("jobs", 0L).containsEntry("users", 0L);
    }

    @Test
    @DisplayName("sources: 适配器状态与入库量对照；库中未被声明的来源单列并标注")
    void sources_mergesAdapterStatusWithCounts() {
        when(jobAgentService.platformStatus()).thenReturn(List.of(
                adapterRow("秋招精选", true, false),
                adapterRow("第三方平台", false, true)));
        when(jobPostingRepository.countGroupByPlatform()).thenReturn(List.<Object[]>of(
                new Object[]{"秋招精选", 6L, 4L},
                new Object[]{"智联招聘", 3L, 3L}));
        when(jobPostingRepository.findLastUpdatedAtByPlatform())
                .thenReturn(List.<Object[]>of(new Object[]{"秋招精选", LocalDateTime.now()}));

        Map<String, Object> result = newService(new SimpleMeterRegistry()).sources();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertThat(items).hasSize(3);

        assertThat(items.get(0))
                .containsEntry("platform", "秋招精选")
                .containsEntry("enabled", true)
                .containsEntry("builtin", true)
                .containsEntry("total", 6L)
                .containsEntry("active", 4L);

        // 聚合适配器（第三方平台）自身不直接入库，标注 aggregate 供前端提示
        assertThat(items.get(1))
                .containsEntry("platform", "第三方平台")
                .containsEntry("aggregate", true)
                .containsEntry("total", 0L);

        // 库中存在但无适配器声明 → 标为未内置，避免「有数据但不知来源」
        assertThat(items.get(2))
                .containsEntry("platform", "智联招聘")
                .containsEntry("builtin", false)
                .containsEntry("enabled", false);

        assertThat(result.get("enabledCount")).isEqualTo(1L);
    }

    /** 构造 platformStatus() 的返回行（与 JobAgentService 的输出结构一致） */
    private static Map<String, Object> adapterRow(String platform, boolean enabled, boolean aggregate) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("platform", platform);
        m.put("enabled", enabled);
        m.put("aggregate", aggregate);
        return m;
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
        when(userRepository.findById(1L)).thenReturn(java.util.Optional.of(
                UserEntity.builder().id(1L).username("普通用户").build()));
        when(userRepository.findById(2L)).thenReturn(java.util.Optional.empty());

        AdminService service = newService(new SimpleMeterRegistry());
        service.banUser(1L, 99L);
        assertThat(userBanRegistry.isBanned(1L)).isTrue();
        service.unbanUser(1L);
        assertThat(userBanRegistry.isBanned(1L)).isFalse();

        assertThatThrownBy(() -> service.banUser(2L, 99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("用户不存在");
        assertThatThrownBy(() -> service.unbanUser(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("用户不存在");
    }

    @Test
    @DisplayName("P2-07: 不能禁用自己，也不能禁用最后一个可用管理员（误禁将永久锁死管理后台）")
    void banUser_selfAndLastAdminGuarded() {
        when(userRepository.findById(7L)).thenReturn(java.util.Optional.of(
                UserEntity.builder().id(7L).username("小吴同学").build()));
        when(userRepository.findById(8L)).thenReturn(java.util.Optional.of(
                UserEntity.builder().id(8L).username("小张同学").build()));

        AdminService service = newService(new SimpleMeterRegistry());

        // ① 禁用自己 → 拒绝（请求者 id 与目标 id 相同）
        assertThatThrownBy(() -> service.banUser(7L, 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能禁用当前登录的管理员账号");
        assertThat(userBanRegistry.isBanned(7L)).isFalse();

        // ② 名单内只剩一个可用管理员（id=7）→ 任何请求者都禁不掉，避免管理后台永久锁死
        when(userRepository.findAll()).thenReturn(List.of(
                UserEntity.builder().id(7L).username("小吴同学").build()));
        assertThatThrownBy(() -> service.banUser(7L, 99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最后一个管理员");
        assertThat(userBanRegistry.isBanned(7L)).isFalse();

        // ③ 名单内有第二个可用管理员时放行（保护不应变成「管理员永远禁不掉」）
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "adminUsernames", "小吴同学,小张同学");
        when(userRepository.findAll()).thenReturn(List.of(
                UserEntity.builder().id(7L).username("小吴同学").build(),
                UserEntity.builder().id(8L).username("小张同学").build()));
        service.banUser(8L, 99L);
        assertThat(userBanRegistry.isBanned(8L)).isTrue();
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
        // P2-D：检索缓存指标（FunctionCounter，与生产侧 RagSearchService 的绑定方式一致）
        io.micrometer.core.instrument.FunctionCounter.builder("rag.search.cache.hit", 11.0, v -> v).register(registry);
        io.micrometer.core.instrument.FunctionCounter.builder("rag.search.cache.miss", 4.0, v -> v).register(registry);
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

        // P2-D：检索缓存命中/未命中进入管理后台指标
        @SuppressWarnings("unchecked")
        Map<String, Object> searchCache = (Map<String, Object>) cache.get("searchCache");
        assertThat(searchCache).isNotNull();
        assertThat(searchCache.get("hits")).isEqualTo(11L);
        assertThat(searchCache.get("misses")).isEqualTo(4L);

        @SuppressWarnings("unchecked")
        Map<String, Object> sse = (Map<String, Object>) metrics.get("sse");
        assertThat(sse.get("maxConcurrent")).isEqualTo(12);
        assertThat(sse.get("activeCount")).isEqualTo(4);

        @SuppressWarnings("unchecked")
        Map<String, Object> jvm = (Map<String, Object>) metrics.get("jvm");
        assertThat((Long) jvm.get("totalMb")).isPositive();
    }

    @Test
    @DisplayName("metrics: 耗时窗口有样本时 p95Ms 必须是数字（P2-03 回归锁定，不能为 0）")
    void metrics_p95FromLatencyWindow() {
        com.example.interview.config.AiLatencyWindow window =
                new com.example.interview.config.AiLatencyWindow();
        window.recordMillis(100);
        window.recordMillis(1000);
        window.recordMillis(10000);

        AdminService service = newService(new SimpleMeterRegistry());
        service.setAiLatencyWindow(window);

        @SuppressWarnings("unchecked")
        Map<String, Object> aiCalls = (Map<String, Object>) service.metrics().get("aiCalls");

        assertThat(aiCalls.get("p95Ms")).isInstanceOf(Double.class);
        assertThat((Double) aiCalls.get("p95Ms")).isEqualTo(10000.0);
        assertThat((Double) aiCalls.get("p95Ms")).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("metrics: 空注册表时各指标回退默认值（counter/timer/gauge 为 null 分支）")
    void metrics_emptyRegistry_fallbacks() {
        Map<String, Object> metrics = newService(new SimpleMeterRegistry()).metrics();

        @SuppressWarnings("unchecked")
        Map<String, Object> aiCalls = (Map<String, Object>) metrics.get("aiCalls");
        // timer 为 null → 不返回 totalCalls / avgMs
        assertThat(aiCalls).doesNotContainKeys("totalCalls", "avgMs");
        // P2-03：p95Ms 现在始终存在，但无样本时为 null（前端渲染「—」而不是 0）。
        // 旧断言要求该键不存在，是「无数据就别显示」的写法；改为显式 null 后，
        // 前端能显示一行「P95 耗时(ms) —」，比整行消失更能说明「暂无样本」。
        assertThat(aiCalls).containsKey("p95Ms");
        assertThat(aiCalls.get("p95Ms")).isNull();
        for (String type : List.of("resume", "question", "evaluate", "jobAnalysis", "rag")) {
            assertThat(aiCalls.get(type)).isEqualTo(0L);
        }

        // P2-D：检索缓存计数器未注册时回退 0，且区块始终存在
        @SuppressWarnings("unchecked")
        Map<String, Object> cache = (Map<String, Object>) metrics.get("cache");
        @SuppressWarnings("unchecked")
        Map<String, Object> searchCache = (Map<String, Object>) cache.get("searchCache");
        assertThat(searchCache.get("hits")).isEqualTo(0L);
        assertThat(searchCache.get("misses")).isEqualTo(0L);

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
