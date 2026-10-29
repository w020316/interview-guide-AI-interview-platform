package com.example.interview.service.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Workday 官方招聘站适配器基类（v1.88.0）
 *
 * <p><b>为什么加这一类源</b>：国内求职者常投的外企（英伟达、思科等）在自己的
 * Workday 招聘站上公开了中国区岗位，接口是站点自身前端调用的 JSON POST，
 * 无需 Key / Cookie。国内大厂官网源（腾讯/美团/网易）之外，这是另一块真实在招数据。
 *
 * <p><b>接口</b>：{@code POST https://{tenant}.wd5.myworkdayjobs.com/wday/cxs/{tenant}/{site}/jobs}
 * —— 请求体 {@code {appliedFacets,limit,offset,searchText}}；岗位详情在
 * {@code .../jobs/{externalPath}}（GET）。实测英伟达 245 个中国岗位、思科 11 个。
 *
 * <p><b>「只取中国」怎么保证</b>：Workday 的 facet id 因租户而异，不能写死。
 * 首次刷新会先发一个不带 facet 的请求（limit=1），从响应的 {@code facets} 里找出
 * 国家级「China」或城市级「..., China」的 id，缓存后用于后续所有请求。
 * 找不到时退回 {@code searchText=China} + 列表地点含 China 的保守过滤
 * （可能漏掉「多地点」岗位，但不会把海外岗位混进国内栏）。
 *
 * <p><b>字段取舍（不编造）</b>：Workday 不公开薪资、学历、经验与截止日期 → 全部留空；
 * 地点只做「China, X → 中文城市名」的词面映射，未知城市保留英文原名；
 * 招聘类型只按标题里明确的 Intern / New College Graduate 判定。
 *
 * <p><b>详情失败降级</b>：列表项本身可用，单条 JD 拉取失败只让该条 description 留空，
 * 不让整个源失败——否则一次详情抖动会丢掉整页岗位。
 */
public abstract class AbstractWorkdayJobProvider extends AbstractOpenApiJobProvider {

    private static final Logger log = LoggerFactory.getLogger(AbstractWorkdayJobProvider.class);

    /** 实测 20 条/页稳定且响应体适中 */
    protected static final int PAGE_SIZE = 20;

    /** 取不到 total 时的保守页数上限 */
    private static final int FALLBACK_MAX_PAGE = 1;

    /** 详情熔断的最小样本量：低于这个数全失败可能只是巧合，不值得让整源失败 */
    private static final int MIN_DETAIL_SAMPLE = 3;

    private static final Pattern INTERN_WORD = Pattern.compile("\\bintern(ship)?\\b", Pattern.CASE_INSENSITIVE);

    /** 裸子串 {@code "ux"} 会命中 Luxury / Auxiliary，必须用词边界 */
    private static final Pattern UX_WORD = Pattern.compile("\\bux\\b", Pattern.CASE_INSENSITIVE);

    private final java.util.concurrent.atomic.AtomicInteger pageCursor =
            new java.util.concurrent.atomic.AtomicInteger(0);

    private volatile int maxPage = FALLBACK_MAX_PAGE;

    /** China facet 只发现一次，随后缓存（租户的 facet id 极少变化） */
    private volatile ChinaFacet chinaFacet;
    private volatile boolean facetResolved;

    protected AbstractWorkdayJobProvider(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    // ────────────────────────── 子类提供 ──────────────────────────

    /** Workday 主机，如 {@code nvidia.wd5.myworkdayjobs.com} */
    protected abstract String workdayHost();

    /** 租户标识（URL 中两处出现），如 {@code nvidia} */
    protected abstract String tenant();

    /** 站点标识，如 {@code NVIDIAExternalCareerSite} */
    protected abstract String site();

    /** 展示用公司名，如 {@code NVIDIA} */
    protected abstract String companyName();

    /** externalId 前缀，如 {@code nv} */
    protected abstract String externalIdPrefix();

    /** 每轮拉取几页，默认 1（由各子类用 @Value 注入） */
    protected int pagesPerRefresh() {
        return 1;
    }

    // ────────────────────────── 抓取主流程 ──────────────────────────

    @Override
    protected String endpoint() {
        return listUrl();
    }

    /** 国内岗位：不进「海外远程」分栏 */
    @Override
    public boolean overseas() {
        return false;
    }

    @Override
    protected int maxItemsPerRefresh() {
        return PAGE_SIZE;
    }

    @Override
    public List<JobDto> fetch() {
        ChinaFacet facet = chinaFacet();
        boolean fallback = facet == null;
        int pages = Math.max(1, pagesPerRefresh());
        List<JobDto> all = new ArrayList<>();
        int detailAttempts = 0;
        detailFailureCount = 0;
        for (int i = 0; i < pages; i++) {
            String body = buildBody(nextPage() * PAGE_SIZE, facet);
            JsonNode root;
            try {
                root = objectMapper.readTree(fetchRawPost(listUrl(), body, referer()));
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                log.warn("公开数据源 {} 数据解析失败：{}", platform(), e.getMessage());
                throw new IllegalStateException("数据解析失败：" + e.getMessage(), e);
            }
            updateMaxPage(root);
            for (WorkdayListing listing : listings(root, fallback)) {
                detailAttempts++;
                all.add(enrichWithDetail(listing));
            }
        }
        // 详情整体失效（端点变更 / 被限流）时不能让健康表记 success：那样管理后台是绿灯，
        // 而所有 JD 正文都是 null，「源挂了」和「源正常但没正文」就无法区分了。
        // 样本量下限 3：一两条全失败可能只是巧合，不值得让整源失败。
        if (detailAttempts >= MIN_DETAIL_SAMPLE && detailFailureCount == detailAttempts) {
            throw new IllegalStateException(
                    "详情接口全部失败（" + detailAttempts + " 条），JD 正文缺失");
        }
        return all;
    }

    /** China facet 发现（找不到则返回 null，走 searchText 兜底） */
    private ChinaFacet chinaFacet() {
        if (facetResolved) {
            return chinaFacet;
        }
        try {
            JsonNode root = objectMapper.readTree(
                    fetchRawPost(listUrl(), buildBody(0, null), referer()));
            // ⚠️ 这里**不能**调 updateMaxPage：此请求未带 facet，total 是全站数量
            // （思科 22），而按 China facet 过滤后只有 11 → 会把 maxPage 污染成 2，
            // 导致主循环白跑一轮空页。页数交给带 facet 的列表请求去定。
            chinaFacet = findChinaFacet(root);
            facetResolved = true;
            if (chinaFacet == null) {
                log.warn("公开数据源 {} 未找到 China facet，本轮起使用 searchText=China 兜底过滤", platform());
            } else {
                log.info("公开数据源 {} 发现 China facet：{}（{} 个 id）",
                        platform(), chinaFacet.parameter(), chinaFacet.ids().size());
            }
            return chinaFacet;
        } catch (Exception e) {
            // 发现失败不缓存，下轮重试；本轮退回 searchText 兜底
            log.warn("公开数据源 {} China facet 发现失败，本轮使用 searchText 兜底：{}", platform(), e.getMessage());
            return null;
        }
    }

    /** 列表请求体（facet 为空时用 searchText=China 兜底） */
    static String buildBody(int offset, ChinaFacet facet) {
        String facets = facet == null
                ? "{}"
                : "{\"" + facet.parameter() + "\":["
                    + facet.ids().stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(","))
                    + "]}";
        String search = facet == null ? "China" : "";
        return "{\"appliedFacets\":" + facets + ",\"limit\":" + PAGE_SIZE
                + ",\"offset\":" + offset + ",\"searchText\":\"" + search + "\"}";
    }

    /**
     * 从 facets 响应里找中国：
     * 优先国家级（descriptor 恰好为 China），否则退到城市级（descriptor 以 ", China" 结尾）。
     */
    static ChinaFacet findChinaFacet(JsonNode root) {
        JsonNode facets = root == null ? null : root.path("facets");
        if (facets == null || !facets.isArray()) {
            return null;
        }
        List<ChinaFacet> cityLevel = new ArrayList<>();
        for (JsonNode facet : facets) {
            ChinaFacet country = scanFacetGroup(facet, cityLevel);
            if (country != null) {
                return country;
            }
        }
        return cityLevel.isEmpty() ? null : cityLevel.get(0);
    }

    private static ChinaFacet scanFacetGroup(JsonNode group, List<ChinaFacet> cityLevel) {
        if (group == null || !group.isObject()) {
            return null;
        }
        String param = group.path("facetParameter").asText(null);
        JsonNode values = group.get("values");
        if (param != null && values != null && values.isArray()) {
            for (JsonNode v : values) {
                if ("China".equals(v.path("descriptor").asText(""))) {
                    String id = v.path("id").asText(null);
                    if (id != null) {
                        return new ChinaFacet(param, List.of(id));
                    }
                }
            }
            List<String> cityIds = new ArrayList<>();
            for (JsonNode v : values) {
                String descriptor = v.path("descriptor").asText("");
                if (descriptor.endsWith(", China")) {
                    String id = v.path("id").asText(null);
                    if (id != null) {
                        cityIds.add(id);
                    }
                }
            }
            if (!cityIds.isEmpty()) {
                cityLevel.add(new ChinaFacet(param, cityIds));
            }
        }
        if (values != null && values.isArray()) {
            for (JsonNode v : values) {
                if (v.has("values")) {
                    ChinaFacet found = scanFacetGroup(v, cityLevel);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 页数由 {@code total} 推导。
     *
     * <p><b>⚠️ 陷阱</b>：Workday 只在 {@code offset=0} 的响应里返回真实 {@code total}，
     * {@code offset>0} 时返回 {@code total=0}（实测 NVIDIA / Cisco 均如此）。
     * 若照单全收，第一轮之后 {@code maxPage} 会被 0 覆盖成 1，游标永远停在首页。
     * 因此这里对非正数直接跳过——缺失/非法时保留原上限，避免抖动。
     * 改动此方法前请先确认上游这个行为是否仍然成立。
     *
     * <p>页数变小后游标可能越界，这里顺手取模归位（同时防止长期运行后 int 溢出）。
     */
    private void updateMaxPage(JsonNode root) {
        JsonNode total = root == null ? null : root.path("total");
        if (total == null || !total.isNumber() || total.asLong() <= 0) {
            return;
        }
        int pages = (int) Math.ceil(total.asLong() / (double) PAGE_SIZE);
        maxPage = Math.max(1, pages);
        pageCursor.updateAndGet(p -> p % maxPage);
    }

    int maxPage() {
        return maxPage;
    }

    /**
     * 取本次要请求的页号并推进游标。
     *
     * <p>游标是**单调递增**的，取模只在这里做：{@code maxPage} 在首次请求前还是
     * 保守值 1（facet 发现请求不参与推导，避免未过滤的 total 污染页数），
     * 若让游标按 {@code maxPage} 回绕，第一次就前进不了，后续永远停在首页。
     */
    private int nextPage() {
        int limit = Math.max(1, maxPage);
        int current = pageCursor.getAndUpdate(p -> p + 1);
        return current % limit;
    }

    /**
     * 纯列表解析（不打详情）。{@code requireChinaText} 为 true 表示 facet 不可用，
     * 只能按地点文本过滤——宁可漏掉「多地点」岗位，也不把海外岗位混进来。
     */
    List<WorkdayListing> listings(JsonNode root, boolean requireChinaText) {
        List<WorkdayListing> result = new ArrayList<>();
        JsonNode postings = root == null ? null : root.path("jobPostings");
        if (postings == null || !postings.isArray()) {
            return result;
        }
        for (JsonNode node : postings) {
            String title = text(node, "title");
            String path = text(node, "externalPath");
            if (title == null || path == null) {
                continue;
            }
            String locationsText = text(node, "locationsText");
            if (requireChinaText && !containsChina(locationsText)) {
                continue;
            }
            String reqId = bulletId(node);
            if (reqId == null) {
                reqId = lastPathSegment(path);
            }
            if (reqId == null) {
                continue;
            }
            JobDto dto = new JobDto(
                    clip(externalIdPrefix() + "-" + reqId, LEN_EXTERNAL_ID),
                    clip(title, LEN_TITLE),
                    clip(companyName(), LEN_COMPANY),
                    null,                                   // 行业不预设，交给分类服务
                    jobTypeOf(title),
                    clip(locationOf(locationsText, null), LEN_LOCATION),
                    null,                                   // 薪资未公开
                    null,                                   // 学历未提供
                    null,                                   // 经验未提供
                    recruitTypeOf(title),
                    null,                                   // 截止日期未提供
                    clip(publicUrl(path), LEN_URL),
                    null,                                   // 详情补正文
                    null,
                    null                                    // 标签交给分类服务
            );
            result.add(new WorkdayListing(path, dto));
        }
        return result;
    }

    /** 供契约测试使用的纯列表解析（不做中国过滤） */
    @Override
    protected List<JobDto> parse(JsonNode root) {
        return listings(root, false).stream().map(WorkdayListing::dto).toList();
    }

    /** 本轮详情拉取失败次数（单线程刷新，用普通字段即可） */
    private int detailFailureCount;

    /** 详情补 JD；单条失败只让该条 description 留空，全部失败由 {@link #fetch()} 抛出 */
    private JobDto enrichWithDetail(WorkdayListing listing) {
        try {
            JsonNode info = objectMapper.readTree(fetchRaw(detailUrl(listing.externalPath())))
                    .path("jobPostingInfo");
            String title = text(info, "title");
            if (title == null) {
                title = listing.dto().title();
            }
            String location = locationOf(text(info, "location"), listing.dto().location());
            String applyUrl = text(info, "externalUrl");
            if (applyUrl == null) {
                applyUrl = listing.dto().applyUrl();
            }
            return new JobDto(
                    listing.dto().externalId(),
                    clip(title, LEN_TITLE),
                    listing.dto().companyName(),
                    null,
                    jobTypeOf(title),
                    clip(location, LEN_LOCATION),
                    null,
                    null,
                    null,
                    recruitTypeOf(title),
                    null,
                    clip(applyUrl, LEN_URL),
                    plainText(text(info, "jobDescription"), DESC_MAX_LEN),
                    null,
                    null
            );
        } catch (Exception e) {
            detailFailureCount++;
            log.warn("{} 岗位 {} 详情拉取失败，仅入库列表字段：{}",
                    platform(), listing.dto().externalId(), e.getMessage());
            return listing.dto();
        }
    }

    // ────────────────────────── 字段工具 ──────────────────────────

    private String listUrl() {
        return "https://" + workdayHost() + "/wday/cxs/" + tenant() + "/" + site() + "/jobs";
    }

    private String referer() {
        return "https://" + workdayHost() + "/" + site();
    }

    private String detailUrl(String externalPath) {
        String path = externalPath.startsWith("/") ? externalPath : "/" + externalPath;
        return "https://" + workdayHost() + "/wday/cxs/" + tenant() + "/" + site() + path;
    }

    private String publicUrl(String externalPath) {
        String path = externalPath.startsWith("/") ? externalPath : "/" + externalPath;
        return "https://" + workdayHost() + "/" + site() + path;
    }

    private static String bulletId(JsonNode node) {
        JsonNode bullets = node == null ? null : node.get("bulletFields");
        if (bullets == null || !bullets.isArray() || bullets.isEmpty()) {
            return null;
        }
        String id = bullets.get(0).asText("").trim();
        return id.isBlank() ? null : id;
    }

    private static String lastPathSegment(String path) {
        int slash = path.lastIndexOf('/');
        String seg = slash >= 0 ? path.substring(slash + 1) : path;
        seg = seg.replaceAll("_[A-Za-z0-9]+$", "").trim();
        return seg.isBlank() ? null : seg;
    }

    static boolean containsChina(String locationsText) {
        return locationsText != null
                && (locationsText.contains("China") || locationsText.contains("中国"));
    }

    /**
     * 地点归一：{@code China, Shanghai → 上海}；多地点（{@code 3 Locations}）→ 中国；
     * 未知城市保留原文（不猜）。
     */
    static String locationOf(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String s = raw.trim();
        if (s.matches("\\d+\\s+Locations?")) {
            return "中国";
        }
        String city = null;
        if ("China".equals(s)) {
            return "中国";
        }
        // 两种上游写法都存在：NVIDIA 用「China, Shanghai」，思科用「Shenzhen, China」
        if (s.startsWith("China,")) {
            city = s.substring("China,".length()).trim();
        } else if (s.endsWith(", China")) {
            city = s.substring(0, s.length() - ", China".length()).trim();
        }
        if (city != null) {
            return city.isEmpty() ? "中国" : CITY_ZH.getOrDefault(city, city);
        }
        return fallback != null ? fallback : s;
    }

    private static final java.util.Map<String, String> CITY_ZH = java.util.Map.ofEntries(
            java.util.Map.entry("Beijing", "北京"),
            java.util.Map.entry("Shanghai", "上海"),
            java.util.Map.entry("Shenzhen", "深圳"),
            java.util.Map.entry("Hangzhou", "杭州"),
            java.util.Map.entry("Chengdu", "成都"),
            java.util.Map.entry("Guangzhou", "广州"),
            java.util.Map.entry("Nanjing", "南京"),
            java.util.Map.entry("Suzhou", "苏州"),
            java.util.Map.entry("Wuhan", "武汉"),
            java.util.Map.entry("Xi'an", "西安"),
            java.util.Map.entry("Xian", "西安"),
            java.util.Map.entry("Xiamen", "厦门"),
            java.util.Map.entry("Dalian", "大连"),
            java.util.Map.entry("Tianjin", "天津"),
            java.util.Map.entry("Qingdao", "青岛"),
            java.util.Map.entry("Hefei", "合肥"),
            java.util.Map.entry("Changsha", "长沙"),
            java.util.Map.entry("Chongqing", "重庆"),
            java.util.Map.entry("Zhuhai", "珠海"),
            java.util.Map.entry("Wuxi", "无锡"),
            java.util.Map.entry("Hong Kong", "香港"),
            java.util.Map.entry("Macau", "澳门"),
            java.util.Map.entry("Remote", "远程")
    );

    /** 招聘类型：只认标题里明确写出的词 */
    static String recruitTypeOf(String title) {
        String t = title == null ? "" : title;
        if (INTERN_WORD.matcher(t).find() || t.contains("实习")) {
            return "INTERN";
        }
        String lower = t.toLowerCase();
        if (lower.contains("new college graduate") || lower.contains("campus")
                || lower.contains("university graduate") || t.contains("校招")) {
            return "AUTUMN";
        }
        return "SOCIAL";
    }

    /**
     * 英文岗位名 → 系统 jobType。只映射词面等价的类别，其余留空交给分类服务。
     *
     * <p><b>为什么不能用 {@code "design "}（带尾随空格）</b>：实测 NVIDIA 245 条里
     * 被判「设计」的 18 条中有 11 条是硬件工程岗 —— {@code ASIC Physical Design Engineer}、
     * {@code System Design Engineer}、{@code Silicon Co-Design Group} 等，
     * 因为 {@code "design "} 会命中 {@code design engineer} / {@code chip design -} /
     * {@code co-design group}。设计桶只认 {@code designer} 这类明确的岗位名词。
     *
     * <p><b>为什么 {@code ux} 要用词边界</b>：裸子串会命中 {@code Auxiliary}、{@code Luxury}，
     * 把 {@code Luxury Brand Manager} 判成设计岗。
     *
     * <p><b>{@code data } 收窄为词组</b>：否则 {@code Data Center Operations Manager}
     * 会被判成技术岗而不是运营岗。
     */
    static String jobTypeOf(String title) {
        String t = title == null ? "" : title.toLowerCase();
        if (containsAny(t, "designer", "ui design", "visual design", "ux design", "design lead")
                || UX_WORD.matcher(t).find()) return "设计";
        if (containsAny(t, "engineer", "developer", "architect", "software", "hardware",
                "firmware", "scientist", "researcher", "technical", "programmer",
                "devops", "sre", "security", "asic", "verification",
                "data engineer", "data scientist", "data analyst", "data architect")) return "技术";
        if (containsAny(t, "product manager", "product owner", "product management")) return "产品";
        if (containsAny(t, "marketing", "brand", "communications", "growth")) return "市场";
        if (containsAny(t, "sales", "account manager", "business development", "business manager")) return "销售";
        if (containsAny(t, "operations", "program manager", "project manager")) return "运营";
        if (containsAny(t, "recruiter", "talent", "human resources", " hr ", "finance",
                "financial", "accounting", "legal", "tax", "administrative", "facilities")) return "职能";
        return null;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }

    /** 列表项 + 详情路径 */
    record WorkdayListing(String externalPath, JobDto dto) {
    }

    /** 用于 appliedFacets 的中国 facet */
    record ChinaFacet(String parameter, List<String> ids) {
    }
}
