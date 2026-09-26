package com.example.interview.service;

import com.example.interview.common.ResourceNotFoundException;
import com.example.interview.config.AiLatencyWindow;
import com.example.interview.entity.JobPostingEntity;
import com.example.interview.entity.UserEntity;
import com.example.interview.repository.JobPostingRepository;
import com.example.interview.repository.UserRepository;
import com.example.interview.service.job.JobAgentService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 管理后台服务（v1.31.4 建立，v1.37.0 扩展数据源视图与趋势统计）
 *
 * <ul>
 *   <li>数据总览：岗位与用户总量、数据源分布、招聘类型分布、热门行业、近 7 天趋势</li>
 *   <li>手动刷新（复用 {@link JobAgentService}）</li>
 *   <li>岗位管理：分页检索（含失效）+ 来源/类型/状态筛选 + 下架/恢复/删除</li>
 *   <li>用户管理：分页列表（含禁用状态）、禁用/解禁</li>
 *   <li>数据源视图：适配器启用状态 × 实际入库量 × 最近更新时间（v1.37.0）</li>
 *   <li>系统指标：AI 调用统计、SSE 并发水位、JVM 内存</li>
 * </ul>
 */
@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    /** 总览趋势窗口（天） */
    private static final int TREND_DAYS = 7;

    /**
     * 管理员名单（与 {@code JwtUtil} 同一配置项 app.admin-usernames）——
     * 仅用于 P2-07 的「最后一个管理员」保护，不参与鉴权（鉴权仍走 JWT 的 role claim）。
     */
    @Value("${app.admin-usernames:}")
    private String adminUsernames;

    private final JobPostingRepository jobPostingRepository;
    private final UserRepository userRepository;
    private final JobAgentService jobAgentService;
    private final UserBanRegistry userBanRegistry;
    private final MeterRegistry meterRegistry;

    public AdminService(JobPostingRepository jobPostingRepository,
                        UserRepository userRepository,
                        JobAgentService jobAgentService,
                        UserBanRegistry userBanRegistry,
                        MeterRegistry meterRegistry) {
        this.jobPostingRepository = jobPostingRepository;
        this.userRepository = userRepository;
        this.jobAgentService = jobAgentService;
        this.userBanRegistry = userBanRegistry;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 进程内自算的 AI 耗时窗口（第三轮 P2-03）。
     *
     * <p>用 **setter 注入**而非构造器参数：构造器已被多处测试直接调用，加参数会波及全部调用点。
     * 声明为可选（{@code required = false}）以便纯单测在不搭 Spring 上下文时也能构造本类。
     */
    private AiLatencyWindow aiLatencyWindow;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAiLatencyWindow(AiLatencyWindow aiLatencyWindow) {
        this.aiLatencyWindow = aiLatencyWindow;
    }

    // ── 数据总览 ──

    /**
     * 总览：岗位总量/有效量/失效量、用户总量、最近刷新时间、刷新中状态，
     * 以及数据源分布、招聘类型分布、热门行业与近 7 天趋势（v1.37.0）。
     */
    public Map<String, Object> overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        long totalJobs = jobPostingRepository.count();
        long activeJobs = jobPostingRepository.countByActiveTrue();
        result.put("totalJobs", totalJobs);
        result.put("activeJobs", activeJobs);
        result.put("inactiveJobs", totalJobs - activeJobs);
        result.put("totalUsers", userRepository.count());
        result.put("bannedUsers", userBanRegistry.bannedSnapshot().size());
        result.put("lastRefreshedAt", jobPostingRepository.findLastUpdatedAt());
        result.put("refreshing", jobAgentService.isRefreshing());
        result.put("sourceDist", sourceDistribution());
        result.put("recruitDist", recruitDistribution());
        result.put("trend", buildTrend(TREND_DAYS));
        // v1.38.0：数据源拉取失败的告警列表——某个源被上游停用/网络不可达时，
        // 此前只能靠「岗位总数慢慢变少」察觉，现在总览直接给出待处理的源
        result.put("sourceAlerts", jobAgentService.sourceAlerts());
        return result;
    }

    /** 数据源分布（按有效岗位数降序，同时给出失效数便于发现「某个源集体过期」） */
    private List<Map<String, Object>> sourceDistribution() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] row : jobPostingRepository.countGroupByPlatform()) {
            long total = toLong(row[1]);
            long active = toLong(row[2]);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("platform", String.valueOf(row[0]));
            item.put("total", total);
            item.put("active", active);
            item.put("inactive", total - active);
            rows.add(item);
        }
        return rows;
    }

    /** 招聘类型分布（秋招/春招/实习/社招/定向），用于总览的比例条 */
    private Map<String, Long> recruitDistribution() {
        Map<String, Long> dist = new LinkedHashMap<>();
        for (Object[] row : jobPostingRepository.countByRecruitType()) {
            dist.put(String.valueOf(row[0]), toLong(row[1]));
        }
        return dist;
    }

    /**
     * 近 N 天「新增岗位 / 新增用户」趋势。
     *
     * <p>刻意不在 SQL 里按天分组：`DATE()` / `CAST(... AS date)` 在 H2（本地/测试）
     * 与 PostgreSQL（生产）上的写法不一致，容易「本地绿、线上红」。
     * 改为取回时间戳后在 Java 侧归组，方言无关且数据量可控。
     */
    private List<Map<String, Object>> buildTrend(int days) {
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(days - 1L);
        LocalDateTime since = from.atStartOfDay();

        Map<LocalDate, Long> jobsByDay = countByDay(jobPostingRepository.findCreatedAtSince(since));
        Map<LocalDate, Long> usersByDay = countByDay(userRepository.findCreatedAtSince(since));

        List<Map<String, Object>> trend = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            LocalDate day = from.plusDays(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", day.toString());
            row.put("label", day.getMonthValue() + "/" + day.getDayOfMonth());
            row.put("jobs", jobsByDay.getOrDefault(day, 0L));
            row.put("users", usersByDay.getOrDefault(day, 0L));
            trend.add(row);
        }
        return trend;
    }

    private static Map<LocalDate, Long> countByDay(List<LocalDateTime> timestamps) {
        Map<LocalDate, Long> map = new HashMap<>();
        if (timestamps == null) {
            return map;
        }
        for (LocalDateTime ts : timestamps) {
            if (ts != null) {
                map.merge(ts.toLocalDate(), 1L, Long::sum);
            }
        }
        return map;
    }

    /** 手动刷新岗位数据（管理员调用，无按用户限流） */
    public JobAgentService.RefreshResult refreshJobs() {
        JobAgentService.RefreshResult result = jobAgentService.refresh();
        if (result == null) {
            throw new IllegalStateException("岗位数据正在刷新中，请稍后再试");
        }
        return result;
    }

    // ── 数据源视图（v1.37.0）──

    /**
     * 数据源健康视图：把「适配器声明了哪些源、是否启用」与「库中实际有多少岗位、
     * 最近何时更新」对照展示。
     *
     * <p>关键价值：刷新失败或某个源被平台停用时，此前在后台完全看不出来——
     * 只有岗位总数慢慢变少。现在能直接看到每个源的贡献量与最后更新时间。
     *
     * <p>库里存在但没有任何适配器声明的来源（历史第三方渠道、已关闭的数据源）
     * 也会列出并标注，避免「数据在但不知道哪来的」。
     */
    public Map<String, Object> sources() {
        Map<String, long[]> stat = new LinkedHashMap<>();
        for (Object[] row : jobPostingRepository.countGroupByPlatform()) {
            stat.put(String.valueOf(row[0]), new long[]{toLong(row[1]), toLong(row[2])});
        }
        Map<String, LocalDateTime> lastUpdated = new LinkedHashMap<>();
        for (Object[] row : jobPostingRepository.findLastUpdatedAtByPlatform()) {
            if (row[1] instanceof LocalDateTime ldt) {
                lastUpdated.put(String.valueOf(row[0]), ldt);
            }
        }

        List<Map<String, Object>> adapterStatus = jobAgentService.platformStatus();
        Set<String> declared = new LinkedHashSet<>();
        for (Map<String, Object> a : adapterStatus) {
            declared.add(String.valueOf(a.get("platform")));
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        long enabledCount = 0;
        for (Map<String, Object> a : adapterStatus) {
            String name = String.valueOf(a.get("platform"));
            boolean enabled = Boolean.TRUE.equals(a.get("enabled"));
            boolean aggregate = Boolean.TRUE.equals(a.get("aggregate"));
            if (enabled) {
                enabledCount++;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("platform", name);
            row.put("enabled", enabled);
            row.put("aggregate", aggregate);
            row.put("builtin", true);
            row.put("overseas", a.get("overseas"));
            // v1.38.0：最近一次拉取的健康快照（成败/条数/耗时/错误摘要/连续失败次数）
            row.put("health", a.get("health"));
            long[] s = stat.getOrDefault(name, new long[]{0L, 0L});
            row.put("total", s[0]);
            row.put("active", s[1]);
            row.put("lastUpdatedAt", lastUpdated.get(name));
            row.put("note", aggregate
                    ? "按渠道名分别入库，实际来源见下方标记为「渠道」的条目"
                    : null);
            rows.add(row);
        }

        for (Map.Entry<String, long[]> e : stat.entrySet()) {
            if (declared.contains(e.getKey())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("platform", e.getKey());
            row.put("enabled", false);
            row.put("aggregate", false);
            row.put("builtin", false);
            row.put("total", e.getValue()[0]);
            row.put("active", e.getValue()[1]);
            row.put("lastUpdatedAt", lastUpdated.get(e.getKey()));
            row.put("note", "当前没有适配器申明该来源（可能是已配置的第三方渠道或已关闭的数据源）");
            rows.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", rows);
        result.put("count", rows.size());
        result.put("enabledCount", enabledCount);
        return result;
    }

    // ── 岗位数据管理 ──

    /**
     * 全部岗位分页（含失效）。
     *
     * @param keyword     模糊匹配标题/公司/标签
     * @param source      精确匹配数据来源（platform）
     * @param recruitType 精确匹配招聘类型（AUTUMN/SPRING/SOCIAL/INTERN/TARGETED）
     * @param active      状态筛选：true 仅有效 / false 仅失效 / null 全部
     */
    public Page<JobPostingEntity> listJobs(String keyword, String source, String recruitType,
                                           Boolean active, Boolean overseas, int page, int size) {
        var spec = (org.springframework.data.jpa.domain.Specification<JobPostingEntity>) (root, query, cb) -> cb.conjunction();
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim().toLowerCase();
            String pattern = "%" + kw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("title")), pattern, '\\'),
                    cb.like(cb.lower(root.get("companyName")), pattern, '\\'),
                    cb.like(cb.lower(root.get("tags")), pattern, '\\')));
        }
        if (source != null && !source.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("platform"), source.trim()));
        }
        if (recruitType != null && !recruitType.isBlank()) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("recruitType"), recruitType.trim()));
        }
        if (active != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("active"), active));
        }
        // P3-D：海外/国内范围过滤（口径与用户侧招聘广场一致——海外源由适配器 self-declare）。
        // 默认 null=不限；管理端前端默认传 false（国内），否则按 id 倒序的首屏
        // 会被最近批量导入的海外源整屏占据，看不到国内数据。
        if (overseas != null) {
            List<String> overseasNames = jobAgentService.overseasPlatforms();
            if (!overseasNames.isEmpty()) {
                if (overseas) {
                    spec = spec.and((root, query, cb) -> root.get("platform").in(overseasNames));
                } else {
                    spec = spec.and((root, query, cb) -> cb.not(root.get("platform").in(overseasNames)));
                }
            }
        }
        // 默认按 id 倒序：新增的岗位排前面，便于运营快速确认「刚刷新的数据进来了」
        var pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 50),
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Order.desc("id")));
        return jobPostingRepository.findAll(spec, pageable);
    }

    /** 下架岗位（active=false） */
    public void deactivateJob(Long id) {
        JobPostingEntity job = jobPostingRepository.findById(id).orElse(null);
        if (job == null) {
            // P3-01：操作一个已不存在的岗位是「资源不存在」，应为 404 而非 400
            throw new ResourceNotFoundException("岗位不存在");
        }
        job.setActive(false);
        jobPostingRepository.save(job);
    }

    /** 恢复岗位（active=true） */
    public void activateJob(Long id) {
        JobPostingEntity job = jobPostingRepository.findById(id).orElse(null);
        if (job == null) {
            // P3-01：同上，「查不到实体」应为 404
            throw new ResourceNotFoundException("岗位不存在");
        }
        job.setActive(true);
        jobPostingRepository.save(job);
    }

    /** 删除岗位 */
    public void deleteJob(Long id) {
        if (!jobPostingRepository.existsById(id)) {
            // P3-01：删除一个已不存在的岗位是「资源不存在」，应为 404
            throw new ResourceNotFoundException("岗位不存在");
        }
        jobPostingRepository.deleteById(id);
    }

    // ── 用户管理 ──

    /** 用户分页列表（脱敏：不含 passwordHash） */
    public Page<UserView> listUsers(String keyword, int page, int size) {
        var spec = (org.springframework.data.jpa.domain.Specification<UserEntity>) (root, query, cb) -> cb.conjunction();
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim().toLowerCase();
            String pattern = "%" + kw + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("username")), pattern),
                    cb.like(cb.lower(root.get("email")), pattern)));
        }
        var pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 50),
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Order.desc("id")));
        Page<UserEntity> entities = userRepository.findAll(spec, pageable);
        return entities.map(u -> new UserView(
                u.getId(), u.getUsername(), u.getEmail(), u.getCreatedAt(),
                userBanRegistry.isBanned(u.getId())));
    }

    /** 用户视图 DTO（不暴露 passwordHash） */
    public record UserView(Long id, String username, String email, LocalDateTime createdAt, boolean banned) {
    }

    /**
     * 禁用用户。
     *
     * <p><b>封禁是持久化的</b>：{@link UserBanRegistry} 自 v1.33.0 起写穿 Redis，
     * 重启时经 loadFromRedis 恢复 —— 前端文案不得再称「重启自动恢复」（P2-06）。
     *
     * <p><b>P2-07 加固</b>：封禁是「不可逆且没有解封入口」的操作 —— 被禁用户在
     * JwtAuthFilter 阶段即被拒绝、登录也返回 403，而管理员名单
     * （app.admin-usernames）默认只配置了一个账号。一旦误禁自己或最后一个管理员，
     * 管理后台将永久无法访问，只能改 Redis / 环境变量人工恢复。故在此拦截两种情形。
     *
     * @param id            目标用户 ID
     * @param currentUserId 当前登录管理员 ID（用于「不能禁用自己」判定）；为 null 时跳过该校验
     */
    public void banUser(Long id, Long currentUserId) {
        UserEntity target = requireUser(id);

        if (currentUserId != null && currentUserId.equals(id)) {
            throw new IllegalArgumentException("不能禁用当前登录的管理员账号");
        }

        if (isConfiguredAdmin(target.getUsername()) && countAvailableAdmins() <= 1) {
            throw new IllegalArgumentException("系统需保留至少一个可用管理员，无法禁用最后一个管理员账号");
        }

        userBanRegistry.ban(id);
        log.info("管理员禁用用户 id={} username={}", id, target.getUsername());
    }

    /** 解禁用户 */
    public void unbanUser(Long id) {
        requireUser(id);
        userBanRegistry.unban(id);
        log.info("管理员解禁用户 id={}", id);
    }

    /** 校验用户存在并返回实体（封禁前需要 username 判定目标是否为管理员） */
    private UserEntity requireUser(Long id) {
        if (id == null) {
            // P3-01 例外：这里 id 来自 @PathVariable Long（Spring 已把非数字值拦成
            // MethodArgumentTypeMismatchException → 400），走到这一步只可能是「参数缺失」，
            // 属调用方 bug 而非「资源不存在」，故保持 400 语义不变。
            throw new IllegalArgumentException("用户不存在");
        }
        // P3-01：findById 查不到是「资源不存在」，应为 404
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("用户不存在"));
    }

    /** 用户名是否在配置的管理员名单内（大小写不敏感） */
    private boolean isConfiguredAdmin(String username) {
        if (username == null) return false;
        return configuredAdminNames().contains(username.toLowerCase(Locale.ROOT));
    }

    /** 解析 app.admin-usernames（逗号分隔，小写归一化） */
    private Set<String> configuredAdminNames() {
        if (adminUsernames == null || adminUsernames.isBlank()) return Set.of();
        return Arrays.stream(adminUsernames.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    /**
     * 统计「名单内且当前未被禁用」的管理员数量。
     *
     * 仅在目标命中管理员名单时才被调用（封禁本身是低频管理操作），
     * 因此直接全表扫描换取实现简单 —— 不为一次性判定新增仓储查询。
     */
    private long countAvailableAdmins() {
        Set<String> names = configuredAdminNames();
        if (names.isEmpty()) return 0;
        return userRepository.findAll().stream()
                .filter(u -> u.getUsername() != null && names.contains(u.getUsername().toLowerCase(Locale.ROOT)))
                .filter(u -> !userBanRegistry.isBanned(u.getId()))
                .count();
    }

    // ── 系统指标 ──

    /** 系统指标：AI 调用计数/耗时、缓存命中、SSE 水位、JVM 内存 */
    public Map<String, Object> metrics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("aiCalls", aiCallSummary());
        result.put("cache", cacheSummary());
        result.put("sse", sseSummary());
        result.put("jvm", jvmSummary());
        return result;
    }

    private Map<String, Object> aiCallSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String type : List.of("resume", "question", "evaluate", "jobAnalysis", "rag")) {
            var counter = meterRegistry.find("ai.call.count").tag("type", type).counter();
            summary.put(type, counter == null ? 0L : (long) counter.count());
        }
        var timer = meterRegistry.find("ai.call.duration").timer();
        if (timer != null) {
            summary.put("totalCalls", timer.count());
            summary.put("avgMs", timer.mean(java.util.concurrent.TimeUnit.MILLISECONDS));
        }
        // 2026-09-26（第三轮 P2-03）：P95 改为进程内自算。
        // 线上 Micrometer 的客户端百分位长期恒为 0，而同一响应里 totalCalls / avgMs 正常，
        // 出现「平均 8659.8ms 但 P95 为 0」这种自相矛盾的指标；8 组对照实验证明配置本身正确、
        // 根因未能在进程内复现（详见 AiLatencyWindow 类注释），因此不再依赖该行为。
        // 无样本时返回 null，前端渲染「—」，与同页「缓存命中率」的口径保持一致。
        summary.put("p95Ms", aiLatencyWindow == null ? null : aiLatencyWindow.p95());
        return summary;
    }

    private Map<String, Object> cacheSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        var hit = meterRegistry.find("cache.hit.count").counter();
        var miss = meterRegistry.find("cache.miss.count").counter();
        summary.put("hits", hit == null ? 0L : (long) hit.count());
        summary.put("misses", miss == null ? 0L : (long) miss.count());
        // P2-D：RAG 检索缓存（P2-09 引入）此前未接入 Micrometer，管理后台恒显示 0/0。
        // 计数器由 RagSearchService 启动时以 FunctionCounter 绑定（rag.search.cache.*）。
        Map<String, Object> searchCache = new LinkedHashMap<>();
        var searchHit = meterRegistry.find("rag.search.cache.hit").functionCounter();
        var searchMiss = meterRegistry.find("rag.search.cache.miss").functionCounter();
        searchCache.put("hits", searchHit == null ? 0L : (long) searchHit.count());
        searchCache.put("misses", searchMiss == null ? 0L : (long) searchMiss.count());
        summary.put("searchCache", searchCache);
        return summary;
    }

    private Map<String, Object> sseSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        var max = meterRegistry.find("sse.max.concurrent").gauge();
        var active = meterRegistry.find("sse.active.count").gauge();
        summary.put("maxConcurrent", max == null ? 20 : (int) max.value());
        summary.put("activeCount", active == null ? 0 : (int) active.value());
        return summary;
    }

    private Map<String, Object> jvmSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        Runtime rt = Runtime.getRuntime();
        summary.put("usedMb", (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024);
        summary.put("freeMb", rt.freeMemory() / 1024 / 1024);
        summary.put("totalMb", rt.totalMemory() / 1024 / 1024);
        summary.put("maxMb", rt.maxMemory() / 1024 / 1024);
        return summary;
    }

    /** JPQL 聚合结果可能是 Long / Integer / BigInteger，统一转 long（SUM 无匹配时为 null） */
    private static long toLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
