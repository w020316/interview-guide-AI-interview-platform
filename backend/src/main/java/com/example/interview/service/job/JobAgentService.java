package com.example.interview.service.job;

import com.example.interview.entity.JobPostingEntity;
import com.example.interview.repository.JobPostingRepository;
import com.example.interview.service.job.JobPlatformAdapter.JobDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 招聘信息智能体服务
 *
 * 职责：
 * 1. 调度各平台适配器拉取岗位并幂等 upsert（保证数据准确性与时效性）
 * 2. AI 智能分类补全（行业/职位类型/标签）
 * 3. 过期岗位自动下架 + 长期失效数据清理（数据更新机制）
 * 4. 多条件筛选搜索（关键词/行业/职位类型/地点/招聘类型/来源）
 */
@Service
public class JobAgentService {

    private static final Logger log = LoggerFactory.getLogger(JobAgentService.class);

    private final JobPostingRepository repository;
    private final List<JobPlatformAdapter> adapters;
    private final HttpJobPlatformAdapter httpAdapter;
    private final JobClassifyService classifyService;
    private final JobSourceHealthRegistry healthRegistry;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    /** 刷新互斥锁：手动刷新与定时任务并发时后到者跳过（单实例部署，实例内互斥已足够） */
    private final java.util.concurrent.atomic.AtomicBoolean refreshRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public JobAgentService(JobPostingRepository repository,
                           List<JobPlatformAdapter> adapters,
                           HttpJobPlatformAdapter httpAdapter,
                           JobClassifyService classifyService,
                           JobSourceHealthRegistry healthRegistry,
                           org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.adapters = adapters;
        this.httpAdapter = httpAdapter;
        this.classifyService = classifyService;
        this.healthRegistry = healthRegistry;
        this.transactionTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    /** 刷新结果统计 */
    public record RefreshResult(int upserted, int inserted, int updated, int expired, int removed) {
    }

    /** 是否有刷新任务正在执行（管理后台总览展示） */
    public boolean isRefreshing() {
        return refreshRunning.get();
    }

    /**
     * 全量刷新：拉取所有启用平台数据并入库
     * - 内置精选等适配器：走 fetch()，platform = adapter.platform()
     * - 第三方平台（智联招聘/前程无忧/BOSS直聘）：走 fetchAllByPlatform()，按平台展示名分组入库
     *
     * v1.23.1 修复（BE-02/03）：
     * - 移除 refresh() 上的 @Transactional：事务不再包裹外部 HTTP 拉取与 AI 分类（此前会长时间
     *   占用连接，生产 Hikari 池仅 2 连接，导致全站接口排队超时）。单条 save 自带短事务，
     *   清理类 @Modifying 查询用 TransactionTemplate 包裹
     * - 新增互斥锁：手动刷新与定时任务并发时后到者返回 null（Controller 转为友好提示），
     *   避免并发 upsert 撞 UNIQUE(platform, external_id) 约束
     *
     * @return 刷新统计；已有刷新任务在执行时返回 null
     */
    public RefreshResult refresh() {
        if (!refreshRunning.compareAndSet(false, true)) {
            log.info("已有岗位刷新任务在执行，本次触发跳过");
            return null;
        }
        try {
            return doRefresh();
        } finally {
            refreshRunning.set(false);
        }
    }

    private RefreshResult doRefresh() {
        int upserted = 0;
        int inserted = 0;
        int updated = 0;

        // 分组收集：平台名 -> 岗位列表
        Map<String, List<JobDto>> platformJobs = new LinkedHashMap<>();
        for (JobPlatformAdapter adapter : adapters) {
            if (adapter == httpAdapter) {
                continue; // 第三方平台单独处理
            }
            String name = adapter.platform();
            // v1.38.0 修复：此前漏了 isEnabled 判断，open-api-enabled=false 只能让
            // 管理后台显示「未启用」，实际仍会向海外 API 发请求——开关形同虚设
            if (!adapter.isEnabled()) {
                log.info("数据源 {} 已停用，本轮跳过", name);
                continue;
            }
            // 低频源节流：公开 API 按各自 minRefreshIntervalMs 冷却，避免反复打扰上游
            if (healthRegistry.isCoolingDown(name, adapter.minRefreshIntervalMs())) {
                log.info("数据源 {} 处于刷新冷却期，本轮跳过", name);
                continue;
            }
            long startedAt = System.currentTimeMillis();
            try {
                List<JobDto> fetched = adapter.fetch();
                platformJobs.put(name, fetched);
                healthRegistry.recordSuccess(name, fetched == null ? 0 : fetched.size(),
                        System.currentTimeMillis() - startedAt);
            } catch (Exception e) {
                // 失败隔离：单个源失败不影响其他源；同时登记健康状态，供管理后台告警
                log.warn("平台 {} 岗位拉取失败：{}", name, e.getMessage());
                healthRegistry.recordFailure(name, e.getMessage(), System.currentTimeMillis() - startedAt);
            }
        }
        try {
            long startedAt = System.currentTimeMillis();
            Map<String, List<JobDto>> thirdParty = httpAdapter.fetchAllByPlatform();
            platformJobs.putAll(thirdParty);
            // 第三方适配器是「一个适配器按渠道名展开」，因此逐渠道登记健康状态
            for (var entry : thirdParty.entrySet()) {
                healthRegistry.recordSuccess(entry.getKey(),
                        entry.getValue() == null ? 0 : entry.getValue().size(),
                        System.currentTimeMillis() - startedAt);
            }
        } catch (Exception e) {
            log.warn("第三方平台岗位拉取失败：{}", e.getMessage());
            healthRegistry.recordFailure("第三方平台", e.getMessage(), 0L);
        }

        // 跨数据源去重：同一真实岗位可能被多个 provider 收录（不同 externalId 但同公司+同岗位），
        // 按归一化 公司+岗位 只保留首次出现的记录，避免招聘广场重复堆量。
        Set<String> dedup = new HashSet<>();

        for (var entry : platformJobs.entrySet()) {
            String platform = entry.getKey();
            List<JobDto> jobs = entry.getValue();
            if (jobs == null || jobs.isEmpty()) {
                continue;
            }
            try {
                // AI 分类：仅为缺失行业/职位类型的数据补全
                List<JobDto> needClassify = jobs.stream()
                        .filter(j -> isBlank(j.industry()) || isBlank(j.jobType()))
                        .toList();
                Map<String, JobClassifyService.Classification> clsMap = new HashMap<>();
                if (!needClassify.isEmpty()) {
                    List<JobClassifyService.Classification> cls = classifyService.classifyBatch(needClassify);
                    for (int i = 0; i < needClassify.size() && i < cls.size(); i++) {
                        clsMap.put(needClassify.get(i).externalId(), cls.get(i));
                    }
                }

                // P1/S-03：先收集本平台去重后的待写入项，再一次性批量查库与保存，
                // 消除逐条 findByPlatformAndExternalId + save 的 N+1 写库
                List<PendingUpsert> pending = new ArrayList<>();
                for (JobDto dto : jobs) {
                    // 去重：同一 refresh 内不同 provider 收录的同公司同岗位，仅保留首次
                    if (!dedup.add(dedupKey(dto))) {
                        continue;
                    }
                    pending.add(new PendingUpsert(dto, clsMap.get(dto.externalId())));
                }
                UpsertResult r = upsertBatch(platform, pending);
                upserted += pending.size();
                inserted += r.inserted();
                updated += r.updated();
                log.info("平台 {} 岗位刷新完成：{} 条", platform, jobs.size());
            } catch (Exception e) {
                // 单平台失败不影响整体刷新
                log.warn("平台 {} 岗位刷新失败：{}", platform, e.getMessage());
            }
        }

        // 数据更新机制：截止日期已过 30 天的下架；非内置数据 60 天未更新则清理
        // @Modifying 查询需要事务：用 TransactionTemplate 短事务包裹，不再依赖外部长事务
        Integer expired = transactionTemplate.execute(status ->
                repository.deactivateExpired(LocalDate.now().minusDays(30)));
        Integer removed = transactionTemplate.execute(status ->
                repository.deleteStaleThirdParty("内置精选", LocalDateTime.now().minusDays(60)));

        log.info("招聘信息刷新完成：新增 {} / 更新 {} / 下架 {} / 清理 {}", inserted, updated, expired, removed);
        return new RefreshResult(upserted, inserted, updated,
                expired == null ? 0 : expired, removed == null ? 0 : removed);
    }

    /** 待写入岗位：dto + 其 AI/规则分类补全结果 */
    private record PendingUpsert(JobDto dto, JobClassifyService.Classification cls) {}

    /** 批量 upsert 结果：inserted 新增数 / updated 更新数 */
    private record UpsertResult(int inserted, int updated) {}

    /**
     * 批量幂等 upsert（P1/S-03，2026-09-20）
     *
     * <p>原实现逐条 {@code findByPlatformAndExternalId + save}，第三方平台全量刷新时
     * 每条岗位产生 2 次 DB 往返，数百条即数百次额外查询，Hikari 连接被长时间占用。
     * 现改为：一次 IN 查询取回存量实体 → 内存完成字段装配 → saveAll 一次性写库。
     *
     * <p>整批包在同一个短事务中，获得平台级原子性：任一条失败整批回滚、
     * 下轮刷新重试，避免「半批成功」的中间状态（原逐条实现失败时已写入的部分会残留）。
     *
     * @param platform 平台标识
     * @param pending  去重后的待写入项
     * @return 新增 / 更新条数
     */
    private UpsertResult upsertBatch(String platform, List<PendingUpsert> pending) {
        if (pending.isEmpty()) {
            return new UpsertResult(0, 0);
        }
        List<String> externalIds = new ArrayList<>(pending.size());
        for (PendingUpsert p : pending) {
            externalIds.add(p.dto().externalId());
        }
        // 同一 externalId 理论上唯一（uk 约束），putIfAbsent 防御异常数据
        Map<String, JobPostingEntity> existing = new HashMap<>();
        for (JobPostingEntity e : repository.findByPlatformAndExternalIdIn(platform, externalIds)) {
            existing.putIfAbsent(e.getExternalId(), e);
        }

        List<JobPostingEntity> toSave = new ArrayList<>(pending.size());
        int inserted = 0;
        for (PendingUpsert p : pending) {
            JobDto dto = p.dto();
            JobPostingEntity entity = existing.get(dto.externalId());
            if (entity == null) {
                entity = JobPostingEntity.builder()
                        .platform(platform)
                        .externalId(dto.externalId())
                        .build();
                inserted++;
            }
            applyJobFields(entity, dto, p.cls());
            toSave.add(entity);
        }
        transactionTemplate.execute(status -> repository.saveAll(toSave));
        return new UpsertResult(inserted, toSave.size() - inserted);
    }

    /** 字段装配（新增与更新共用，与原逐条 upsert 保持完全一致的赋值与兜底规则） */
    private void applyJobFields(JobPostingEntity entity, JobDto dto, JobClassifyService.Classification cls) {
        entity.setTitle(dto.title());
        entity.setCompanyName(dto.companyName());
        entity.setApplyUrl(dto.applyUrl());
        entity.setDescription(dto.description());
        entity.setRequirements(dto.requirements());
        entity.setRecruitType(isBlank(dto.recruitType()) ? "AUTUMN" : dto.recruitType());
        entity.setDeadline(dto.deadline());
        entity.setLocation(dto.location());
        entity.setSalary(dto.salary());
        entity.setDegree(JobFieldNormalizer.normalizeDegree(dto.degree()));
        entity.setExperience(JobFieldNormalizer.normalizeExperience(dto.experience()));
        entity.setActive(true);

        // 行业/职位类型：优先用平台数据，缺失时用 AI/规则分类补全
        entity.setIndustry(!isBlank(dto.industry()) ? dto.industry()
                : (cls != null && !isBlank(cls.industry()) ? cls.industry() : entity.getIndustry()));
        entity.setJobType(!isBlank(dto.jobType()) ? dto.jobType()
                : (cls != null && !isBlank(cls.jobType()) ? cls.jobType() : entity.getJobType()));
        entity.setTags(!isBlank(dto.tags()) ? dto.tags()
                : (cls != null && !isBlank(cls.tags()) ? cls.tags() : entity.getTags()));
    }

    /** 岗位详情（仅返回有效岗位） */
    public JobPostingEntity findById(Long id) {
        return repository.findById(id).filter(j -> Boolean.TRUE.equals(j.getActive())).orElse(null);
    }

    /**
     * 多条件筛选搜索（Specification 动态查询）
     * 说明：不使用 @Query 的 ":param IS NULL OR" 模式——PostgreSQL 无法推断
     * NULL 参数类型导致生产 500（H2 本地正常），Specification 无此问题。
     *
     * @param recruitType AUTUMN 秋招 / SPRING 春招 / SOCIAL 社招 / INTERN 实习 /
     *                    TARGETED 定向专项 / null 全部
     */
    public Page<JobPostingEntity> search(String keyword, String industry, String jobType,
                                         String location, String recruitType, String source,
                                         String degree, String experience,
                                         int page, int size) {
        return search(keyword, industry, jobType, location, recruitType, source, degree, experience,
                null, page, size);
    }

    /**
     * 岗位检索（v1.38.0 增加 overseas 分栏条件）
     *
     * <p>保留上面 10 参数的重载：既有的智能体工具、简历匹配降级路径与测试都按旧签名调用，
     * 全部迁移到新签名的收益（少改 20 处 mock 桩）不足以抵消回归风险。
     *
     * @param overseas true 仅海外/远程数据源；false 仅国内数据源；null 不限。
     *                 数据同库同表，仅按来源类别隔离——国内求职者按「秋招」筛选时
     *                 不会再被欧美远程岗位稀释。
     */
    public Page<JobPostingEntity> search(String keyword, String industry, String jobType,
                                         String location, String recruitType, String source,
                                         String degree, String experience, Boolean overseas,
                                         int page, int size) {
        var spec = org.springframework.data.jpa.domain.Specification.where(emptySpec());
        if (!isBlank(keyword)) {
            // 转义 LIKE 通配符（% _ \），避免用户输入破坏精确匹配语义
            String kw = escapeLike(keyword.trim().toLowerCase());
            String pattern = "%" + kw + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("title")), pattern, '\\'),
                    cb.like(cb.lower(root.get("companyName")), pattern, '\\'),
                    cb.like(cb.lower(root.get("tags")), pattern, '\\')));
        }
        if (!isBlank(industry)) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("industry"), industry.trim()));
        }
        if (!isBlank(jobType)) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("jobType"), jobType.trim()));
        }
        if (!isBlank(location)) {
            spec = spec.and((root, query, cb) -> cb.like(root.get("location"), "%" + location.trim() + "%"));
        }
        if (!isBlank(recruitType)) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("recruitType"), recruitType.trim()));
        }
        if (!isBlank(source)) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("platform"), source.trim()));
        }
        // v1.38.0：海外/远程分栏。海外源清单由适配器自己声明（JobPlatformAdapter#overseas），
        // 而不是在查询层硬编码平台名——将来新增海外源无需改动这里
        if (overseas != null) {
            List<String> overseasNames = overseasPlatforms();
            if (!overseasNames.isEmpty()) {
                if (overseas) {
                    spec = spec.and((root, query, cb) -> root.get("platform").in(overseasNames));
                } else {
                    spec = spec.and((root, query, cb) -> cb.not(root.get("platform").in(overseasNames)));
                }
            }
        }
        // v1.26.0：学历/经验精确筛选（值来自 meta 中的去重列表，与入库值一致）
        if (!isBlank(degree)) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("degree"), degree.trim()));
        }
        if (!isBlank(experience)) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("experience"), experience.trim()));
        }
        spec = spec.and((root, query, cb) -> cb.isTrue(root.get("active")));
        // 排序：临期优先（截止日期升序，空截止日期排后），再按更新时间倒序
        var pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 50),
                org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Order.asc("deadline"),
                        org.springframework.data.domain.Sort.Order.desc("updatedAt")));
        return repository.findAll(spec, pageable);
    }

    private static org.springframework.data.jpa.domain.Specification<JobPostingEntity> emptySpec() {
        return (root, query, cb) -> cb.conjunction();
    }
    /**
     * 筛选面板元数据：行业/职位类型/学历/经验/来源/各招聘类型数量/最近更新时间。
     *
     * @param overseas 分栏口径（P2-08）：true 仅统计海外源、false 仅统计国内源、null 不限。
     *                 此前 recruitCounts 恒为全局聚合，导致「全部国内」分栏下显示「社招 1883」
     *                 而该分栏实际只有 71 条——用户点进去会怀疑数据丢了或分页失效。
     */
    public Map<String, Object> meta(Boolean overseas) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("industries", repository.findDistinctIndustries());
        meta.put("jobTypes", repository.findDistinctJobTypes());
        meta.put("sources", repository.findDistinctPlatforms());
        meta.put("degrees", repository.findDistinctDegrees());
        meta.put("experiences", repository.findDistinctExperiences());

        // 各招聘类型计数：与当前分栏口径保持一致（P2-08）
        List<String> overseasSources = overseasPlatforms();
        List<Object[]> rows;
        if (overseas == null || overseasSources.isEmpty()) {
            // 不分栏，或系统未声明任何海外源（此时分栏过滤无意义）→ 维持全局口径
            rows = repository.countByRecruitType();
        } else if (overseas) {
            rows = repository.countByRecruitTypeInPlatforms(overseasSources);
        } else {
            rows = repository.countByRecruitTypeNotInPlatforms(overseasSources);
        }
        Map<String, Long> recruitCounts = new LinkedHashMap<>();
        for (Object[] row : rows) {
            recruitCounts.put(String.valueOf(row[0]), (Long) row[1]);
        }
        meta.put("recruitCounts", recruitCounts);

        // v1.38.0：海外/远程分栏元数据——前端据此渲染「海外远程」Tab 与角标，
        // 也用于在来源 chips 上区分海外源
        meta.put("overseasSources", overseasSources);
        meta.put("overseasCount", overseasSources.isEmpty() ? 0L : repository.countActiveByPlatformIn(overseasSources));

        LocalDateTime last = repository.findLastUpdatedAt();
        meta.put("lastUpdatedAt", last == null ? null : last.toString());
        return meta;
    }

    /**
     * 全部数据源状态（v1.37.0 建立，v1.38.0 补充海外标识与健康信息）
     *
     * <p>返回每个适配器的展示名、启用状态、是否海外源，以及最近一次拉取的健康快照。
     * 注意第三方 HTTP 适配器的 platform() 返回聚合名「第三方平台」，其实际入库是按各渠道名
     * 展开的——管理后台会把它标注为「按渠道展开」，避免运营者以为有一个叫「第三方平台」
     * 的来源却没有数据。
     */
    public List<Map<String, Object>> platformStatus() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JobPlatformAdapter a : adapters) {
            String name = a.platform();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("platform", name);
            row.put("enabled", a.isEnabled());
            row.put("aggregate", a == httpAdapter);
            row.put("overseas", a.overseas());
            row.put("health", healthRegistry.get(name));
            rows.add(row);
        }
        return rows;
    }

    /**
     * 海外 / 远程数据源展示名清单（v1.38.0）
     *
     * <p>供「海外远程」分栏与筛选使用。由适配器声明而非硬编码平台名，
     * 新增海外源时这里自动跟随。
     */
    public List<String> overseasPlatforms() {
        List<String> names = new ArrayList<>();
        for (JobPlatformAdapter a : adapters) {
            if (a.overseas()) {
                names.add(a.platform());
            }
        }
        return names;
    }

    /** 需要告警的数据源（最近一次失败，或连续失败 ≥ 2 次），供管理后台总览横幅使用 */
    public List<JobSourceHealthRegistry.Health> sourceAlerts() {
        return healthRegistry.alerts();
    }

    /** 定时刷新调度（由 JobRefreshScheduler 调用与手动接口共用） */
    public List<String> enabledPlatforms() {
        List<String> names = new ArrayList<>();
        for (JobPlatformAdapter a : adapters) {
            if (a.isEnabled()) {
                names.add(a.platform());
            }
        }
        return names;
    }

    HttpJobPlatformAdapter httpAdapter() {
        return httpAdapter;
    }

    /** 全部有效岗位（供简历匹配推荐） */
    public List<JobPostingEntity> activeJobs() {
        return repository.findByActiveTrue();
    }

    /**
     * 有效岗位总数（v1.38.0）。
     *
     * <p>供智能体在「没找到」时如实说明库里究竟有多少岗位，用户据此能判断
     * 是「确实没有这类岗位」还是「筛选条件太窄」，而不是误以为平台空空如也。
     * 用 count 查询而非 {@code activeJobs().size()}——后者会把全部岗位实体拉进内存。
     */
    public long activeJobCount() {
        return repository.countByActiveTrue();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** LIKE 通配符转义（防止用户输入 % _ \ 破坏匹配语义） */
    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** 去重键：归一化空白后的 公司|岗位 ，用于跨数据源去重 */
    private static String dedupKey(JobDto dto) {
        String company = dto.companyName() == null ? "" : dto.companyName().trim().replaceAll("\\s+", " ");
        String title = dto.title() == null ? "" : dto.title().trim().replaceAll("\\s+", " ");
        return company + "|" + title;
    }
}
