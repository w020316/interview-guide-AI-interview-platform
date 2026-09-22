package com.example.interview.service.job;

import com.example.interview.entity.JobPostingEntity;
import com.example.interview.repository.JobPostingRepository;
import com.example.interview.service.job.JobPlatformAdapter.JobDto;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyChar;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 招聘信息智能体服务单测
 *
 * <p>两部分：
 * <ul>
 *   <li>Specification 构建与 LIKE 通配符转义（Mockito mock 仓库，保留既有用例）</li>
 *   <li>refresh 幂等 upsert / 去重 / 失败隔离 / 互斥锁、search 动态筛选、meta 聚合
 *       ——使用 @DataJpaTest + 内存 H2 真实仓库，测真实执行逻辑</li>
 * </ul>
 */
@DataJpaTest
@ActiveProfiles("local")
@DisplayName("招聘信息智能体服务测试")
class JobAgentServiceTest {

    private final JobPostingRepository repository = mock(JobPostingRepository.class);
    private final HttpJobPlatformAdapter httpAdapter = mock(HttpJobPlatformAdapter.class);
    private final JobClassifyService classifyService = mock(JobClassifyService.class);
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);

    /**
     * 数据源健康登记表（v1.38.0）用**真实实例**而非 mock：
     * 它本身无外部依赖、纯内存，用真实验例才能顺带验证「刷新流程确实登记了成败」
     * 这一行为，而不是只验证「调用过一次 recordXxx」。
     */
    private final JobSourceHealthRegistry healthRegistry = new JobSourceHealthRegistry();

    /** H2 真实仓库（refresh/upsert/search/meta 用例） */
    @Autowired
    private JobPostingRepository jpaRepository;

    /** H2 事务管理器（@Modifying 清理查询需 TransactionTemplate） */
    @Autowired
    private PlatformTransactionManager jpaTxManager;

    private JobAgentService newService() {
        return new JobAgentService(repository, List.of(), httpAdapter, classifyService, healthRegistry, txManager);
    }

    /** 真实仓库版服务构造 */
    private JobAgentService newH2Service(List<JobPlatformAdapter> adapters) {
        return new JobAgentService(jpaRepository, adapters, httpAdapter, classifyService, healthRegistry, jpaTxManager);
    }

    /** 测试用岗位 DTO（recruitType/deadline 置空走默认分支） */
    private static JobDto dto(String externalId, String title, String company, String industry, String jobType) {
        return new JobDto(externalId, title, company, industry, jobType,
                "深圳", "20k-35k", null, null, null, null,
                "https://apply.example.com/" + externalId, "岗位描述", "岗位要求", null);
    }

    /** 测试用平台适配器（可注入岗位/开关/异常/副作用钩子） */
    private static final class FakeAdapter implements JobPlatformAdapter {
        private final String platform;
        private final List<JobDto> jobs;
        private final boolean enabled;
        private final RuntimeException boom;
        private final Runnable onFetch;

        FakeAdapter(String platform, List<JobDto> jobs) {
            this(platform, jobs, true, null, null);
        }

        FakeAdapter(String platform, List<JobDto> jobs, boolean enabled) {
            this(platform, jobs, enabled, null, null);
        }

        FakeAdapter(String platform, List<JobDto> jobs, RuntimeException boom) {
            this(platform, jobs, true, boom, null);
        }

        FakeAdapter(String platform, List<JobDto> jobs, Runnable onFetch) {
            this(platform, jobs, true, null, onFetch);
        }

        private FakeAdapter(String platform, List<JobDto> jobs, boolean enabled,
                            RuntimeException boom, Runnable onFetch) {
            this.platform = platform;
            this.jobs = jobs;
            this.enabled = enabled;
            this.boom = boom;
            this.onFetch = onFetch;
        }

        @Override
        public String platform() {
            return platform;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public List<JobDto> fetch() {
            if (onFetch != null) {
                onFetch.run();
            }
            if (boom != null) {
                throw boom;
            }
            return jobs;
        }
    }

    // ─────────────────── Specification 构建与 LIKE 转义（既有用例，保持原样） ───────────────────

    /** 关键字中的通配符 % _ \ 必须被转义，避免破坏精确匹配语义 */
    @Test
    void searchKeywordEscapesLikeWildcards() {
        JobAgentService service = newService();
        Page<JobPostingEntity> empty = new PageImpl<>(List.of());
        when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(empty);

        service.search("100%_\\", null, null, null, "AUTUMN", null, null, null, 0, 10);

        // 捕获生成的 Specification 并在 mock 的 Criteria 上求值为 LIKE 谓词，断言转义后的 pattern
        var specCap = org.mockito.ArgumentCaptor.forClass(Specification.class);
        verify(repository).findAll(specCap.capture(), any(Pageable.class));
        @SuppressWarnings("unchecked")
        Specification<JobPostingEntity> spec = specCap.getValue();

        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        @SuppressWarnings("unchecked")
        Root<JobPostingEntity> root = mock(Root.class);
        @SuppressWarnings("unchecked")
        jakarta.persistence.criteria.Path<String> lowerExpr = mock(jakarta.persistence.criteria.Path.class);
        Predicate likePred = mock(Predicate.class);
        Predicate orPred = mock(Predicate.class);
        // root.get(attr) 返回统一 Expression，使 lower/like 谓语非 null 可被断言
        when(root.get(any(String.class))).thenReturn((jakarta.persistence.criteria.Path) lowerExpr);
        when(cb.lower(any(Expression.class))).thenReturn(lowerExpr);
        when(cb.like(eq(lowerExpr), anyString(), anyChar())).thenReturn(likePred);
        when(cb.or(any(Predicate[].class))).thenReturn(orPred);

        spec.toPredicate(root, query, cb);

        // 原始关键字 100%_\ 转义后各 LIKE 使用同一 pattern=%100\%\_\\%
        verify(cb, times(3)).like(eq(lowerExpr), eq("%100\\%\\_\\\\%"), eq('\\'));
    }

    /** 关键字完全省略时不应做 LIKE 匹配，仅保留 active 过滤（不调用 lower） */
    @Test
    void searchWithoutKeywordSkipsLike() {
        JobAgentService service = newService();
        Page<JobPostingEntity> empty = new PageImpl<>(List.of());
        when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(empty);

        service.search(null, null, null, null, "AUTUMN", null, null, null, 0, 10);

        var specCap = org.mockito.ArgumentCaptor.forClass(Specification.class);
        verify(repository).findAll(specCap.capture(), any(Pageable.class));
        @SuppressWarnings("unchecked")
        Specification<JobPostingEntity> spec = specCap.getValue();

        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        @SuppressWarnings("unchecked")
        Root<JobPostingEntity> root = mock(Root.class);
        Predicate conj = mock(Predicate.class);
        when(cb.conjunction()).thenReturn(conj);
        when(cb.isTrue(any(Expression.class))).thenReturn(mock(Predicate.class));
        when(cb.equal(any(Expression.class), any())).thenReturn(mock(Predicate.class));
        when(cb.and(any(Predicate[].class))).thenReturn(mock(Predicate.class));

        spec.toPredicate(root, query, cb);

        verify(cb, times(0)).lower(any(Expression.class));
        verify(root, never()).get("title");
    }

    // ─────────────────── refresh：真实 H2 仓库 upsert / 分类 / 去重 / 隔离 ───────────────────

    @Test
    @DisplayName("refresh: 新岗位经 AI 分类补全后入库（degree/experience 归一化、recruitType 默认）")
    void refresh_insertsNewJobsWithClassification() {
        JobDto needClassify = dto("e1", "Java 后端工程师", "阿里巴巴", null, null);
        JobDto complete = dto("e2", "前端开发工程师", "腾讯", "互联网", "技术");
        when(classifyService.classifyBatch(anyList()))
                .thenReturn(List.of(new JobClassifyService.Classification("互联网", "技术", "校招,高薪")));
        when(httpAdapter.fetchAllByPlatform()).thenReturn(Map.of());

        JobAgentService.RefreshResult result =
                newH2Service(List.of(new FakeAdapter("内置精选", List.of(needClassify, complete)))).refresh();

        assertThat(result.upserted()).isEqualTo(2);
        assertThat(result.inserted()).isEqualTo(2);
        assertThat(result.updated()).isZero();
        assertThat(result.expired()).isZero();
        assertThat(result.removed()).isZero();

        // 仅缺失行业/职位类型的岗位进入 AI 分类
        org.mockito.ArgumentCaptor<List<JobDto>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(classifyService).classifyBatch(captor.capture());
        assertThat(captor.getValue()).containsExactly(needClassify);

        JobPostingEntity saved = jpaRepository.findByPlatformAndExternalId("内置精选", "e1").orElseThrow();
        assertThat(saved.getTitle()).isEqualTo("Java 后端工程师");
        assertThat(saved.getIndustry()).isEqualTo("互联网");
        assertThat(saved.getJobType()).isEqualTo("技术");
        assertThat(saved.getTags()).isEqualTo("校招,高薪");
        assertThat(saved.getRecruitType()).isEqualTo("AUTUMN");
        assertThat(saved.getDegree()).isEqualTo("不限");
        assertThat(saved.getExperience()).isEqualTo("不限");
        assertThat(saved.getActive()).isTrue();

        // 平台已有行业数据的岗位不做 AI 补全（clsMap 无该条 → 保留原值）
        JobPostingEntity e2 = jpaRepository.findByPlatformAndExternalId("内置精选", "e2").orElseThrow();
        assertThat(e2.getIndustry()).isEqualTo("互联网");
        assertThat(e2.getJobType()).isEqualTo("技术");
        assertThat(e2.getTags()).isNull();
    }

    @Test
    @DisplayName("refresh: 同 (platform, externalId) 再次刷新按更新计数，不重复入库")
    void refresh_upsertsExistingJobAsUpdate() {
        jpaRepository.saveAndFlush(JobPostingEntity.builder()
                .platform("内置精选").externalId("e1").title("旧标题").companyName("阿里巴巴")
                .industry("互联网").jobType("技术").active(true).build());
        when(httpAdapter.fetchAllByPlatform()).thenReturn(Map.of());

        JobAgentService.RefreshResult result = newH2Service(List.of(new FakeAdapter("内置精选",
                List.of(dto("e1", "新标题-Java 后端", "阿里巴巴", "互联网", "技术"))))).refresh();

        assertThat(result.upserted()).isEqualTo(1);
        assertThat(result.inserted()).isZero();
        assertThat(result.updated()).isEqualTo(1);
        assertThat(jpaRepository.count()).isEqualTo(1);
        JobPostingEntity saved = jpaRepository.findByPlatformAndExternalId("内置精选", "e1").orElseThrow();
        assertThat(saved.getTitle()).isEqualTo("新标题-Java 后端");
        verifyNoInteractions(classifyService);
    }

    @Test
    @DisplayName("refresh: 跨数据源同公司同岗位去重，仅保留首次出现")
    void refresh_dedupesSameCompanyAndTitleAcrossPlatforms() {
        when(httpAdapter.fetchAllByPlatform()).thenReturn(Map.of());

        JobAgentService.RefreshResult result = newH2Service(List.of(
                new FakeAdapter("内置精选", List.of(dto("a1", "Java 后端工程师", "阿里", "互联网", "技术"))),
                new FakeAdapter("智联招聘", List.of(dto("z1", "Java 后端工程师", "阿里", "互联网", "技术")))
        )).refresh();

        assertThat(result.upserted()).isEqualTo(1);
        assertThat(result.inserted()).isEqualTo(1);
        assertThat(jpaRepository.count()).isEqualTo(1);
        assertThat(jpaRepository.findByPlatformAndExternalId("内置精选", "a1")).isPresent();
        assertThat(jpaRepository.findByPlatformAndExternalId("智联招聘", "z1")).isEmpty();
    }

    @Test
    @DisplayName("refresh: 单平台与第三方拉取失败互相隔离，不影响其他平台入库")
    void refresh_adapterFailureIsolated() {
        when(httpAdapter.fetchAllByPlatform()).thenThrow(new RuntimeException("第三方平台全挂"));

        JobAgentService.RefreshResult result = newH2Service(List.of(
                new FakeAdapter("BOSS直聘", List.of(), new RuntimeException("网络故障")),
                new FakeAdapter("内置精选", List.of(dto("e1", "Java 工程师", "阿里", "互联网", "技术")))
        )).refresh();

        assertThat(result.upserted()).isEqualTo(1);
        assertThat(jpaRepository.findByPlatformAndExternalId("内置精选", "e1")).isPresent();
    }

    @Test
    @DisplayName("refresh: 互斥锁——刷新进行中的并发触发返回 null，结束后锁释放")
    void refresh_concurrentCallReturnsNullAndUnlocks() {
        AtomicReference<JobAgentService> serviceHolder = new AtomicReference<>();
        AtomicReference<JobAgentService.RefreshResult> innerResult = new AtomicReference<>();
        // 在拉取回调中发起内层刷新：互斥锁使内层返回 null
        FakeAdapter adapter = new FakeAdapter("内置精选",
                List.of(dto("e1", "Java 工程师", "阿里", "互联网", "技术")),
                (Runnable) () -> innerResult.set(serviceHolder.get().refresh()));
        JobAgentService service = new JobAgentService(
                jpaRepository, List.of(adapter), httpAdapter, classifyService, healthRegistry, jpaTxManager);
        serviceHolder.set(service);

        JobAgentService.RefreshResult outer = service.refresh();

        assertThat(outer).isNotNull();
        assertThat(innerResult.get()).as("互斥锁应使内层刷新跳过").isNull();
        assertThat(service.isRefreshing()).as("刷新结束后锁应释放").isFalse();
    }

    @Test
    @DisplayName("refresh: httpAdapter 走 fetchAllByPlatform 按平台分组入库，且不参与内置循环")
    void refresh_httpAdapterGroupedByPlatformAndSkippedInBuiltinLoop() {
        Map<String, List<JobDto>> thirdParty = new LinkedHashMap<>();
        thirdParty.put("智联招聘", List.of(dto("z1", "Go 工程师", "字节跳动", "互联网", "技术")));
        when(httpAdapter.fetchAllByPlatform()).thenReturn(thirdParty);

        JobAgentService service = new JobAgentService(
                jpaRepository,
                List.of(new FakeAdapter("内置精选", List.of(dto("e1", "Java 工程师", "阿里", "互联网", "技术"))),
                        httpAdapter),
                httpAdapter, classifyService, healthRegistry, jpaTxManager);

        JobAgentService.RefreshResult result = service.refresh();

        assertThat(result.upserted()).isEqualTo(2);
        verify(httpAdapter, never()).fetch();
        assertThat(jpaRepository.findByPlatformAndExternalId("智联招聘", "z1")).isPresent();
        assertThat(jpaRepository.findByPlatformAndExternalId("内置精选", "e1")).isPresent();
    }

    @Test
    @DisplayName("refresh: 拉取到空列表的平台跳过入库，httpAdapter 访问器返回注入实例")
    void refresh_skipsEmptyPlatformAndExposesHttpAdapter() {
        when(httpAdapter.fetchAllByPlatform()).thenReturn(Map.of());
        JobAgentService service = new JobAgentService(
                jpaRepository,
                List.of(new FakeAdapter("空平台", List.of()),
                        new FakeAdapter("内置精选", List.of(dto("e1", "Java 工程师", "阿里", "互联网", "技术")))),
                httpAdapter, classifyService, healthRegistry, jpaTxManager);

        JobAgentService.RefreshResult result = service.refresh();

        assertThat(result.upserted()).isEqualTo(1);
        assertThat(service.httpAdapter()).isSameAs(httpAdapter);
        assertThat(jpaRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("refresh: AI 分类失败仅隔离该平台，其余平台正常入库")
    void refresh_classifyFailureIsolated() {
        when(classifyService.classifyBatch(anyList())).thenThrow(new RuntimeException("AI 网关不可用"));
        when(httpAdapter.fetchAllByPlatform()).thenReturn(Map.of());

        JobAgentService.RefreshResult result = newH2Service(List.of(
                new FakeAdapter("智联招聘", List.of(dto("z1", "Go 工程师", "字节", null, null))),
                new FakeAdapter("内置精选", List.of(dto("e1", "Java 工程师", "阿里", "互联网", "技术")))
        )).refresh();

        assertThat(result.upserted()).isEqualTo(1);
        assertThat(jpaRepository.findByPlatformAndExternalId("智联招聘", "z1")).isEmpty();
        assertThat(jpaRepository.findByPlatformAndExternalId("内置精选", "e1")).isPresent();
    }

    // ─────────────────── findById / activeJobs / enabledPlatforms ───────────────────

    @Test
    @DisplayName("findById: 仅返回 active 岗位，下架/不存在返回 null")
    void findById_onlyReturnsActive() {
        JobAgentService service = newH2Service(List.of());
        Long activeId = jpaRepository.saveAndFlush(JobPostingEntity.builder()
                .platform("内置精选").externalId("a").title("在招岗位").companyName("某公司")
                .active(true).build()).getId();
        Long inactiveId = jpaRepository.saveAndFlush(JobPostingEntity.builder()
                .platform("内置精选").externalId("b").title("已下架岗位").companyName("某公司")
                .active(false).build()).getId();

        assertThat(service.findById(activeId)).isNotNull();
        assertThat(service.findById(inactiveId)).isNull();
        assertThat(service.findById(999L)).isNull();
    }

    @Test
    @DisplayName("activeJobs: 仅返回 active 岗位列表")
    void activeJobs_returnsActiveOnly() {
        jpaRepository.saveAndFlush(JobPostingEntity.builder()
                .platform("内置精选").externalId("a").title("A").companyName("C").active(true).build());
        jpaRepository.saveAndFlush(JobPostingEntity.builder()
                .platform("内置精选").externalId("b").title("B").companyName("C").active(false).build());

        List<JobPostingEntity> jobs = newH2Service(List.of()).activeJobs();

        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).getTitle()).isEqualTo("A");
    }

    @Test
    @DisplayName("enabledPlatforms: 仅收集 isEnabled 的平台名")
    void enabledPlatforms_returnsEnabledOnly() {
        JobAgentService service = newH2Service(List.of(
                new FakeAdapter("内置精选", List.of(), true),
                new FakeAdapter("BOSS直聘", List.of(), false)));

        assertThat(service.enabledPlatforms()).containsExactly("内置精选");
    }

    // ─────────────────── search：Specification 动态筛选（真实 H2 查询） ───────────────────

    @Test
    @DisplayName("search: 多条件动态筛选命中预期岗位，LIKE 通配符被转义为字面量")
    void search_dynamicFiltersOnRealDatabase() {
        jpaRepository.saveAndFlush(JobPostingEntity.builder()
                .platform("内置精选").externalId("j1").title("Java 后端工程师").companyName("阿里巴巴")
                .industry("互联网").jobType("技术").location("深圳南山").recruitType("AUTUMN")
                .degree("本科").experience("1-3年").tags("java,spring").active(true).build());
        jpaRepository.saveAndFlush(JobPostingEntity.builder()
                .platform("内置精选").externalId("j2").title("前端工程师").companyName("腾讯")
                .industry("互联网").jobType("技术").location("深圳南山").recruitType("AUTUMN")
                .active(false).build());
        jpaRepository.saveAndFlush(JobPostingEntity.builder()
                .platform("智联招聘").externalId("j3").title("量化分析师").companyName("中金公司")
                .industry("金融").jobType("金融").location("上海浦东").recruitType("SOCIAL")
                .degree("硕士").experience("3-5年").active(true).build());

        JobAgentService service = newH2Service(List.of());

        // 无条件：仅 active 的 2 条
        assertThat(service.search(null, null, null, null, null, null, null, null, 0, 10).getTotalElements())
                .isEqualTo(2);
        // 关键词命中标题
        assertThat(service.search("java", null, null, null, null, null, null, null, 0, 10).getTotalElements())
                .isEqualTo(1);
        // 关键词命中公司名
        assertThat(service.search("里巴", null, null, null, null, null, null, null, 0, 10).getTotalElements())
                .isEqualTo(1);
        // 关键词命中标签
        assertThat(service.search("spring", null, null, null, null, null, null, null, 0, 10).getTotalElements())
                .isEqualTo(1);
        // % 作为字面量不应匹配任何岗位（转义生效）
        assertThat(service.search("%", null, null, null, null, null, null, null, 0, 10).getTotalElements())
                .isZero();
        // 各单条件筛选
        assertThat(service.search(null, "互联网", null, null, null, null, null, null, 0, 10).getTotalElements())
                .isEqualTo(1);
        assertThat(service.search(null, null, "技术", null, null, null, null, null, 0, 10).getTotalElements())
                .isEqualTo(1);
        assertThat(service.search(null, null, null, "南山", null, null, null, null, 0, 10).getTotalElements())
                .isEqualTo(1);
        assertThat(service.search(null, null, null, null, "AUTUMN", null, null, null, 0, 10).getTotalElements())
                .isEqualTo(1);
        assertThat(service.search(null, null, null, null, null, "内置精选", null, null, 0, 10).getTotalElements())
                .isEqualTo(1);
        assertThat(service.search(null, null, null, null, null, null, "本科", null, 0, 10).getTotalElements())
                .isEqualTo(1);
        assertThat(service.search(null, null, null, null, null, null, null, "1-3年", 0, 10).getTotalElements())
                .isEqualTo(1);
        // 组合条件 + 分页字段
        Page<JobPostingEntity> page = service.search(null, "互联网", null, "深圳", "AUTUMN", null, "本科", null, 0, 10);
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().get(0).getExternalId()).isEqualTo("j1");
        // 完全不匹配的条件
        assertThat(service.search(null, "教育", null, null, null, null, null, null, 0, 10).getTotalElements())
                .isZero();
    }

    // ─────────────────── meta：筛选面板聚合（真实查询） ───────────────────

    @Test
    @DisplayName("meta: 去重列表、招聘类型计数与最近更新时间来自真实数据")
    @SuppressWarnings("unchecked")
    void meta_aggregatesFromRealData() {
        jpaRepository.saveAndFlush(JobPostingEntity.builder()
                .platform("内置精选").externalId("m1").title("A").companyName("C1")
                .industry("互联网").jobType("技术").recruitType("AUTUMN")
                .degree("本科").experience("应届生").active(true).build());
        jpaRepository.saveAndFlush(JobPostingEntity.builder()
                .platform("智联招聘").externalId("m2").title("B").companyName("C2")
                .industry("金融").jobType("金融").recruitType("SOCIAL")
                .degree("硕士").experience("1-3年").active(true).build());

        Map<String, Object> meta = newH2Service(List.of()).meta();

        assertThat((List<String>) meta.get("industries")).containsExactlyInAnyOrder("互联网", "金融");
        assertThat((List<String>) meta.get("jobTypes")).containsExactlyInAnyOrder("技术", "金融");
        assertThat((List<String>) meta.get("sources")).containsExactlyInAnyOrder("内置精选", "智联招聘");
        assertThat((List<String>) meta.get("degrees")).containsExactlyInAnyOrder("本科", "硕士");
        assertThat((List<String>) meta.get("experiences")).containsExactlyInAnyOrder("应届生", "1-3年");
        Map<String, Long> recruitCounts = (Map<String, Long>) meta.get("recruitCounts");
        assertThat(recruitCounts).containsEntry("AUTUMN", 1L).containsEntry("SOCIAL", 1L);
        assertThat(meta.get("lastUpdatedAt")).isNotNull();
    }

    // ─────────────────── 海外分栏与数据源健康（v1.38.0） ───────────────────

    @Test
    @DisplayName("overseasPlatforms：只返回适配器声明为海外的来源（「海外远程」分栏依赖它）")
    void overseasPlatforms_filtersByAdapterFlag() {
        JobPlatformAdapter overseas = mock(JobPlatformAdapter.class);
        when(overseas.platform()).thenReturn("RemoteOK 全球远程");
        when(overseas.overseas()).thenReturn(true);
        // 国内源只保留默认 overseas()=false；platform() 在本方法中不会被读取
        JobPlatformAdapter domestic = mock(JobPlatformAdapter.class);

        JobAgentService service = new JobAgentService(jpaRepository, List.of(overseas, domestic),
                httpAdapter, classifyService, healthRegistry, jpaTxManager);

        assertThat(service.overseasPlatforms()).containsExactly("RemoteOK 全球远程");
    }

    @Test
    @DisplayName("sourceAlerts：转发健康登记表中的异常源（管理后台告警横幅依赖它）")
    void sourceAlerts_forwardsRegistry() {
        JobAgentService service = new JobAgentService(jpaRepository, List.of(),
                httpAdapter, classifyService, healthRegistry, jpaTxManager);

        assertThat(service.sourceAlerts()).isEmpty();

        healthRegistry.recordFailure("行业精选", "HTTP 403 Forbidden", 120);

        assertThat(service.sourceAlerts())
                .extracting(JobSourceHealthRegistry.Health::platform)
                .containsExactly("行业精选");
    }

    @Test
    @DisplayName("refresh：成功与失败都登记健康状态；未启用的源不参与拉取")
    void refresh_recordsHealthAndSkipsDisabled() {
        JobPlatformAdapter ok = mock(JobPlatformAdapter.class);
        when(ok.platform()).thenReturn("正常源");
        when(ok.isEnabled()).thenReturn(true);
        when(ok.fetch()).thenReturn(List.of(dto("ok1", "Java 工程师", "阿里", "互联网", "技术")));

        JobPlatformAdapter broken = mock(JobPlatformAdapter.class);
        when(broken.platform()).thenReturn("故障源");
        when(broken.isEnabled()).thenReturn(true);
        when(broken.fetch()).thenThrow(new IllegalStateException("拉取失败：HTTP 403"));

        JobPlatformAdapter disabled = mock(JobPlatformAdapter.class);
        when(disabled.platform()).thenReturn("停用源");
        when(disabled.isEnabled()).thenReturn(false);

        when(httpAdapter.fetchAllByPlatform()).thenReturn(Map.of());

        JobAgentService service = new JobAgentService(jpaRepository, List.of(ok, broken, disabled),
                httpAdapter, classifyService, healthRegistry, jpaTxManager);
        service.refresh();

        assertThat(healthRegistry.get("正常源").healthy()).isTrue();
        assertThat(healthRegistry.get("正常源").lastCount()).isEqualTo(1);
        assertThat(healthRegistry.get("故障源").healthy()).isFalse();
        assertThat(healthRegistry.get("故障源").lastError()).contains("HTTP 403");
        // v1.38.0 修复点：启用开关此前只影响后台展示，实际仍会去拉取
        assertThat(healthRegistry.get("停用源")).as("停用的源不应被拉取").isNull();
        verify(disabled, never()).fetch();
    }

    @Test
    @DisplayName("refresh：处于冷却期的低频源本轮跳过（公开 API 6 小时才拉一次）")
    void refresh_skipsCoolingDownSource() {
        JobPlatformAdapter slow = mock(JobPlatformAdapter.class);
        when(slow.platform()).thenReturn("低频源");
        when(slow.isEnabled()).thenReturn(true);
        when(slow.minRefreshIntervalMs()).thenReturn(6 * 3600 * 1000L);
        when(httpAdapter.fetchAllByPlatform()).thenReturn(Map.of());

        // 刚拉过 → 处于冷却期
        healthRegistry.recordSuccess("低频源", 10, 100);

        JobAgentService service = new JobAgentService(jpaRepository, List.of(slow),
                httpAdapter, classifyService, healthRegistry, jpaTxManager);
        service.refresh();

        verify(slow, never()).fetch();
    }

    @Test
    @DisplayName("search：overseas 条件按适配器声明的海外源做 IN / NOT IN 过滤")
    void search_filtersByOverseasFlag() {
        jpaRepository.saveAndFlush(JobPostingEntity.builder()
                .platform("RemoteOK 全球远程").externalId("o1").title("Remote Dev").companyName("Acme")
                .recruitType("SOCIAL").active(true).build());
        jpaRepository.saveAndFlush(JobPostingEntity.builder()
                .platform("行业精选").externalId("d1").title("国内开发").companyName("阿里")
                .recruitType("SOCIAL").active(true).build());

        JobPlatformAdapter overseasAdapter = mock(JobPlatformAdapter.class);
        when(overseasAdapter.platform()).thenReturn("RemoteOK 全球远程");
        when(overseasAdapter.overseas()).thenReturn(true);

        JobAgentService service = new JobAgentService(jpaRepository, List.of(overseasAdapter),
                httpAdapter, classifyService, healthRegistry, jpaTxManager);

        assertThat(service.search(null, null, null, null, null, null, null, null, true, 0, 20).getContent())
                .extracting(JobPostingEntity::getPlatform).containsExactly("RemoteOK 全球远程");

        assertThat(service.search(null, null, null, null, null, null, null, null, false, 0, 20).getContent())
                .extracting(JobPostingEntity::getPlatform)
                .contains("行业精选")
                .doesNotContain("RemoteOK 全球远程");

        // 旧签名（不限来源）仍可用，智能体与简历匹配走的正是这条路径
        assertThat(service.search(null, null, null, null, null, null, null, null, 0, 20).getContent())
                .extracting(JobPostingEntity::getPlatform)
                .contains("行业精选", "RemoteOK 全球远程");
    }
}
