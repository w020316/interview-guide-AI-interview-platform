package com.example.interview.service.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 电鸭社区（国内中文活源，v1.88.0）
 *
 * <p><b>为什么加这个源</b>：国内实时源此前只有腾讯 / 亚马逊中国 / 美团 / 网易 / V2EX，
 * 其中 V2EX 单次只返回约 8 条且无分页。电鸭社区是远程与数字游民方向的招聘社区，
 * 帖子以国内中小团队、初创公司为主，与五个大厂官网源互补。
 *
 * <p><b>接口</b>（2026-10-29 实测）：
 * <ul>
 *   <li>列表 {@code GET https://svc.eleduck.com/api/v1/posts?category=5&page=N}
 *       —— 公开、无需 Key / Cookie，25 条/页，{@code pager.total_pages=410}；</li>
 *   <li>详情 {@code GET https://svc.eleduck.com/api/v1/posts/{id}} —— 返回 {@code {post:{content:...}}}，
 *       正文是 HTML。</li>
 * </ul>
 * 此前 {@link V2exJobsJobProvider} 的注释记录「电鸭 /api/* 全部 404」，实际是路径猜错
 * （公开 API 在 {@code svc.eleduck.com} 子域），本次更正。
 *
 * <p><b>字段取舍（不编造）</b>：
 * <ul>
 *   <li>薪资：只提取标题里明确写出的区间（{@code 15-25K}）或下限（{@code 3K起}），
 *       抽不到就留空——不根据正文里的「面议」「期权」推算；</li>
 *   <li>城市：取帖子的结构化「城市」标签（{@code tag_group.code=city}），
 *       「其他」视为未知；不拿标题里的地名硬猜；</li>
 *   <li>公司名：从标题「XX 招聘」格式里提取，抽不到回退「见岗位详情」，与 V2EX 一致；</li>
 *   <li>学历 / 经验 / 截止日期：上游没有 → 留空；</li>
 *   <li>行业：不预设，交给 {@code JobClassifyService}。</li>
 * </ul>
 *
 * <p>分类 5（{@code category.code=jd}）是「社区帖子招聘」，但版内仍混有求职帖，
 * 与 V2EX 酷工作同样按关键词排除。
 */
@Component
public class EleduckJobProvider extends AbstractOpenApiJobProvider {

    private static final Logger log = LoggerFactory.getLogger(EleduckJobProvider.class);

    private static final String PLATFORM = "电鸭社区";

    /** 实测 25 条/页（pager.limit_value=25） */
    private static final int PAGE_SIZE = 25;

    /** 取不到 {@code pager.total_pages} 时的保守页数上限 */
    private static final int FALLBACK_MAX_PAGE = 10;

    /** 分类 5 = 社区帖子招聘（category.code=jd，kind=work） */
    private static final String LIST_BASE =
            "https://svc.eleduck.com/api/v1/posts?category=5&page=";

    private static final String DETAIL_BASE = "https://svc.eleduck.com/api/v1/posts/";

    private static final String WEB_DETAIL = "https://eleduck.com/posts/";

    /** 出现这些词说明是求职帖而不是招聘帖，直接丢弃（与 V2EX 同一判据） */
    private static final String[] SEEKER_MARKERS = {
            "求职", "求内推", "找工作", "求一份", "个人简历", "求职者", "求推荐",
            "简历求", "找实习", "求实习", "求职中",
    };

    /**
     * 明确不是岗位的帖子。
     *
     * <p><b>为什么必须有这一条</b>：实测第一页 25 条里混着平台的
     * 「【用户举报，涉海外诈骗套路，公开警示⚠️】…」警示帖——它标题里带着
     * 「远程/全职」等词，仅靠「排除求职帖」判据会原样入库，等于把诈骗警示
     * 当成岗位推荐给用户。这是本类唯一一处「宁可少收，不可错收」的过滤。
     *
     * <p><b>为什么用「用户举报」而不是裸「举报」</b>：`举报处理专员`、
     * `举报审核员`、`内容安全-举报治理` 是 B站 / 小红书 / 腾讯内容安全团队的
     * 真实岗位名，裸词根会把它们一起杀掉。警示帖的固定前缀是「用户举报」，
     * 用这个词组既准又不会误伤。
     *
     * <p>同理不要加「曝光」：市场类岗位标题里「品牌曝光」是正常用词。
     */
    private static final String[] NOT_A_JOB_MARKERS = {
            "诈骗", "用户举报", "警示", "避雷", "防骗", "骗局",
    };

    /** 「预算 2000 元」这类是外包接单需求，不是雇佣岗位 */
    private static final java.util.regex.Pattern OUTSOURCE_BUDGET =
            java.util.regex.Pattern.compile("预算\\s*\\d");

    /**
     * 帖子有效期：社区帖生命周期短，超过这个天数的视为已失效。
     *
     * <p><b>为什么必须过滤</b>：游标推进到深页时上游返回的是陈年帖——实测
     * page 100 最老 2022-08-22、page 400 最老 2021-02-18（25/25 全部 closed）。
     * 这些帖的 {@code closed} 字段为 false（版块归档不等于单帖关闭），
     * {@code deadline} 又是 null，过期下架逻辑管不到，只能等 60 天陈旧下架——
     * 而游标 102 天一轮又会把它们复活。用户会看到 2021 年的帖子排在「在招」。
     */
    private static final int MAX_POST_AGE_DAYS = 90;

    /** 详情熔断的最小样本量：低于这个数全失败可能只是巧合，不值得让整源失败 */
    private static final int MIN_DETAIL_SAMPLE = 3;

    /** 实习词边界：裸 {@code contains("intern")} 会把 Internal / International 误判成实习 */
    private static final java.util.regex.Pattern INTERN_WORD =
            java.util.regex.Pattern.compile("\\bintern(ship)?\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** 裸子串 {@code "ux"} / {@code "ui"} 会命中 Luxury、build，必须用词边界 */
    private static final java.util.regex.Pattern UIUX_WORD =
            java.util.regex.Pattern.compile("\\b(ux|ui)\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

    private final java.util.concurrent.atomic.AtomicInteger pageCursor =
            new java.util.concurrent.atomic.AtomicInteger(1);

    private volatile int maxPage = FALLBACK_MAX_PAGE;

    /**
     * 每轮拉取几页，默认 3。
     *
     * <p><b>为什么不是 1</b>：本源的刷新节奏由 {@code AbstractOpenApiJobProvider}
     * 的 {@code minRefreshIntervalMs()} 决定，是 <b>6h/轮</b>（不是调度器的 1h）。
     * 410 页按 1 页/轮需要 410 ÷ 4 ≈ 102 天，**超过 60 天陈旧下架窗口** ——
     * 会出现「入库 → 60 天下架 → 102 天后再抓到 → 复活」的循环，约四成时间岗位不可见。
     * 取 3 页/轮后约 34 天，压进窗口内。
     */
    @org.springframework.beans.factory.annotation.Value("${app.job-agent.eleduck.pages-per-refresh:3}")
    private int pagesPerRefresh = 3;

    public EleduckJobProvider(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public String platform() {
        return PLATFORM;
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
    protected String endpoint() {
        return buildListUrl(1);
    }

    @Override
    public List<JobDto> fetch() {
        int pages = Math.max(1, pagesPerRefresh);
        List<JobDto> all = new ArrayList<>();
        int detailAttempts = 0;
        detailFailureCount = 0;
        for (int i = 0; i < pages; i++) {
            String body = fetchRaw(buildListUrl(nextPage()));
            JsonNode root;
            try {
                root = objectMapper.readTree(body);
            } catch (Exception e) {
                log.warn("公开数据源 {} 数据解析失败：{}", platform(), e.getMessage());
                throw new IllegalStateException("数据解析失败：" + e.getMessage(), e);
            }
            updateMaxPage(root);
            for (JobDto dto : applyQuotaForSource(parse(root))) {
                detailAttempts++;
                all.add(enrichWithDetail(dto));
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

    /** 列表 URL（页码是唯一变量，便于单测断言） */
    static String buildListUrl(int page) {
        return LIST_BASE + page;
    }

    /** 页数即 {@code pager.total_pages}；非法值保留保守上限 */
    static int maxPageOf(long totalPages) {
        if (totalPages <= 0) {
            return FALLBACK_MAX_PAGE;
        }
        return (int) Math.min(totalPages, Integer.MAX_VALUE);
    }

    int maxPage() {
        return maxPage;
    }

    private int nextPage() {
        return pageCursor.getAndUpdate(p -> p >= maxPage ? 1 : p + 1);
    }

    private void updateMaxPage(JsonNode root) {
        JsonNode pages = root == null ? null : root.path("pager").path("total_pages");
        if (pages == null || !pages.isNumber()) {
            return;
        }
        maxPage = maxPageOf(pages.asLong());
    }

    @Override
    protected List<JobDto> parse(JsonNode root) {
        List<JobDto> result = new ArrayList<>();
        JsonNode posts = root == null ? null : root.path("posts");
        if (posts == null || !posts.isArray()) {
            return result;
        }
        for (JsonNode node : posts) {
            String id = text(node, "id");
            String title = text(node, "title");
            // 无稳定 id 无法保证幂等 upsert，丢弃
            if (id == null || title == null) {
                continue;
            }
            if (node.path("closed").asBoolean(false)
                    || node.path("deleted").asBoolean(false)
                    || node.path("hide").asBoolean(false)) {
                continue;
            }
            if (!looksLikeHiring(title)) {
                continue;
            }
            // 深页返回的是陈年帖，closed 字段并不反映单帖失效
            if (!isFresh(node)) {
                continue;
            }
            JsonNode tagsNode = node.get("tags");
            // city 是平台受控词表，「海外」为明确取值 —— 这类帖不该进国内分栏
            if (isOverseasPost(tagsNode)) {
                continue;
            }
            result.add(new JobDto(
                    clip("ed-" + id, LEN_EXTERNAL_ID),
                    clip(title, LEN_TITLE),
                    clip(companyOf(title), LEN_COMPANY),
                    null,                                   // 行业不预设，交给分类服务
                    jobTypeOf(title),
                    clip(cityOf(tagsNode), LEN_LOCATION),
                    clip(salaryOf(title), LEN_SALARY),
                    null,                                   // 学历未提供
                    null,                                   // 经验未提供
                    recruitTypeOf(title),
                    null,                                   // 截止日期未提供
                    clip(WEB_DETAIL + id, LEN_URL),
                    plainText(text(node, "summary"), DESC_MAX_LEN),
                    null,                                   // 帖子正文即岗位描述，无独立要求字段
                    clip(tagNames(tagsNode), LEN_TAGS)
            ));
        }
        return result;
    }

    /** 本轮详情拉取失败次数（单线程刷新，用普通字段即可） */
    private int detailFailureCount;

    /**
     * 详情补正文：列表只有约 200 字摘要，正文在 {@code /posts/{id}}。
     *
     * <p>单条详情失败不抛异常——列表数据本身可用，退回摘要即可；
     * 抛出去会让整轮刷新失败，反而丢掉已经拿到的岗位。
     * 但**全部失败**必须由 {@link #fetch()} 抛出去，见那里的说明。
     */
    private JobDto enrichWithDetail(JobDto dto) {
        String id = dto.externalId().startsWith("ed-")
                ? dto.externalId().substring(3) : dto.externalId();
        try {
            JsonNode root = objectMapper.readTree(fetchRaw(DETAIL_BASE + id));
            JsonNode post = root.path("post");
            String content = text(post, "content", "raw_content");
            String description = plainText(content, DESC_MAX_LEN);
            if (description == null) {
                return dto;
            }
            return new JobDto(dto.externalId(), dto.title(), dto.companyName(), dto.industry(),
                    dto.jobType(), dto.location(), dto.salary(), dto.degree(), dto.experience(),
                    dto.recruitType(), dto.deadline(), dto.applyUrl(), description,
                    dto.requirements(), dto.tags());
        } catch (Exception e) {
            detailFailureCount++;
            log.warn("电鸭帖子 {} 详情拉取失败，退回列表摘要：{}", id, e.getMessage());
            return dto;
        }
    }

    /**
     * 判断是否像一条招聘帖。
     *
     * <p>分类 5 本身是招聘版，所以不做「必须含招聘关键词」的正向判定
     * （大量真实岗位标题只写「高级前端 20-30K」）；改为排除法：
     * 求职帖 → 非岗位帖（诈骗警示）→ 外包接单需求，三类一票否决。
     */
    static boolean looksLikeHiring(String title) {
        if (title == null) return false;
        for (String s : SEEKER_MARKERS) {
            if (title.contains(s)) return false;
        }
        for (String s : NOT_A_JOB_MARKERS) {
            if (title.contains(s)) return false;
        }
        return !OUTSOURCE_BUDGET.matcher(title).find();
    }

    /**
     * 从标题抽取「某某招聘」里的公司名；抽不到返回「见岗位详情」。
     *
     * <p><b>候选串含数字 / 波浪号一律视为抽取失败</b>：实测
     * {@code 急~12K-15K招聘国内远程 海外社媒运营} 会把薪资 {@code 急~12K-15K}
     * 当成公司名写进 {@code company_name} 列，还会污染前端的企业筛选。
     */
    static String companyOf(String title) {
        String t = title.replaceFirst("^\\s*[\\[【(（][^\\]】)）]{1,24}[\\]】)）]\\s*", "");
        for (String kw : new String[]{"招聘", "招人", "诚招", "急招", "招募", "内推"}) {
            int i = t.indexOf(kw);
            if (i > 1) {
                String c = t.substring(0, i).replaceAll("[\\s\\-—|,，、:：/]+$", "").trim();
                if (!c.isBlank() && c.length() <= 40 && !c.matches(".*[\\d~～].*")) {
                    return c;
                }
            }
        }
        return "见岗位详情";
    }

    /** 发布时间在有效期内；上游没给发布时间时不武断丢弃 */
    static boolean isFresh(JsonNode node, java.time.LocalDateTime now) {
        String published = text(node, "published_at");
        if (published == null || published.isBlank()) {
            return true;
        }
        try {
            java.time.OffsetDateTime t = java.time.OffsetDateTime.parse(published);
            return t.toLocalDateTime().isAfter(now.minusDays(MAX_POST_AGE_DAYS));
        } catch (Exception e) {
            // 上游换了时间格式不该让整源失败，保守放行
            return true;
        }
    }

    private static boolean isFresh(JsonNode node) {
        return isFresh(node, java.time.LocalDateTime.now());
    }

    /**
     * 是否海外岗位：电鸭的 {@code city} 是平台受控词表，「海外」「国外」是明确取值
     * （不是自由文本，可以精确匹配）。
     *
     * <p>{@code overseas()} 是**适配器级**开关，无法按条切换，所以在解析阶段直接丢弃，
     * 避免巴黎、新加坡、澳洲这类岗位混进「国内」分栏。
     */
    static boolean isOverseasPost(JsonNode tags) {
        if (tags == null || !tags.isArray()) {
            return false;
        }
        for (JsonNode tag : tags) {
            if (!"city".equals(tag.path("tag_group").path("code").asText(""))) {
                continue;
            }
            String name = tag.path("name").asText("").trim();
            if ("海外".equals(name) || "国外".equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** 城市取自结构化标签（tag_group.code=city）；「其他」视为未知 */
    static String cityOf(JsonNode tags) {
        if (tags == null || !tags.isArray()) {
            return null;
        }
        List<String> cities = new ArrayList<>();
        for (JsonNode tag : tags) {
            if (!"city".equals(tag.path("tag_group").path("code").asText(""))) {
                continue;
            }
            String name = tag.path("name").asText("").trim();
            if (!name.isBlank() && !"其他".equals(name) && !cities.contains(name)) {
                cities.add(name);
            }
        }
        return cities.isEmpty() ? null : String.join("、", cities);
    }

    /** 全部标签名用逗号拼接（前端标签区与关键词检索都用它） */
    static String tagNames(JsonNode tags) {
        if (tags == null || !tags.isArray()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (JsonNode tag : tags) {
            String name = tag.path("name").asText("").trim();
            if (!name.isBlank() && !names.contains(name)) {
                names.add(name);
            }
        }
        return names.isEmpty() ? null : String.join(",", names);
    }

    /**
     * 只提取标题里明确写出的薪资：区间（15-25K / 15K-25K / 23K–32K）或下限（3K起）。
     *
     * <p><b>首个 K 必须可选</b>：社区里最常见的写法是「15-25K」——只写末尾一个 K。
     * 早期正则要求两端各带 K（{@code 15K-25K}），实测会把「15-25K」整条丢掉，
     * 薪资字段静默变空。
     *
     * <p><b>分隔符要覆盖排版变体</b>：实测真实标题里出现过 en dash（U+2013，`23K–32K`），
     * 只认 ASCII 连字符会漏。区间右端仍强制要求 K，否则「2027-2028 届」这类数字区间
     * 会被误判成薪资。
     */
    static String salaryOf(String title) {
        if (title == null) return null;
        java.util.regex.Matcher range = java.util.regex.Pattern
                .compile("(\\d{1,3}(?:\\.\\d)?)\\s*[kK]?\\s*[-~至\\u2013\\u2014]\\s*(\\d{1,3}(?:\\.\\d)?)\\s*[kK]")
                .matcher(title);
        if (range.find()) {
            return range.group(1) + "-" + range.group(2) + "K";
        }
        java.util.regex.Matcher from = java.util.regex.Pattern
                .compile("(\\d{1,3}(?:\\.\\d)?)\\s*[kK]\\s*起")
                .matcher(title);
        return from.find() ? from.group(1) + "K起" : null;
    }

    /** 招聘类型：只认标题里明确写出的词 */
    static String recruitTypeOf(String title) {
        String t = title == null ? "" : title;
        // 词边界匹配：裸 contains("intern") 会把 Internal / International 误判成实习
        if (t.contains("实习") || INTERN_WORD.matcher(t).find()) return "INTERN";
        if (t.contains("校招") || t.contains("秋招") || t.contains("春招")
                || t.contains("应届") || t.contains("校园")) return "AUTUMN";
        return "SOCIAL";
    }

    /**
     * 职位类型：与其它源保持同一套中文枚举。
     *
     * <p><b>识别不出时返回 null，不兜底猜「技术」</b>：社区版块里除了技术岗，
     * 还有咖啡师、店员、外贸业务这类岗位，兜底判技术等于编造，会把它们塞进技术筛选里。
     * 留空交给 {@code JobClassifyService}（空值最终归为「综合」）。
     */
    static String jobTypeOf(String title) {
        String t = title == null ? "" : title.toLowerCase();
        if (matchAny(t, "前端", "后端", "java", "python", "golang", "go ", "rust", "c++",
                "运维", "测试", "算法", "架构", "开发", "工程师", "devops", "sre", "数据",
                "android", "ios", "全栈", "安全", "嵌入式", "ai", "agent", "大模型")) return "技术";
        if (matchAny(t, "产品经理", "产品", "product")) return "产品";
        if (matchAny(t, "设计", "视觉") || UIUX_WORD.matcher(t).find()) return "设计";
        if (matchAny(t, "运营", "operation")) return "运营";
        if (matchAny(t, "市场", "marketing", "增长", "品牌")) return "市场";
        if (matchAny(t, "销售", "sales", "商务", "bd")) return "销售";
        if (matchAny(t, "人事", "hr", "财务", "法务", "行政")) return "职能";
        if (matchAny(t, "客服", "支持", "support")) return "客服";
        return null;
    }

    private static boolean matchAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }
}
