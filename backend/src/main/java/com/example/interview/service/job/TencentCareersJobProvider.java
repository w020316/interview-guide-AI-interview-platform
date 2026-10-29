package com.example.interview.service.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 腾讯招聘（国内实时数据源，v1.80.0）
 *
 * <p><b>为什么加这个源</b>：此前国内岗位几乎全部来自 {@code Seed*.java} 静态种子，
 * 唯一的中文活源是 V2EX 酷工作——而它单次只返回约 8 条、无分页参数。
 * 线上实测「国内 9252 条 : 海外 8875 条」几乎 1:1，而海外有 5 个实时 API 持续产出，
 * 国内几乎没有活水。这是「国外岗位太多」的真正根因。
 *
 * <p><b>接口</b>：{@code GET https://careers.tencent.com/tencentcareer/api/post/Query}
 * —— 公开、无需 Key、无需登录、结构化 JSON。实测 {@code Count=2287} 条在招岗位，
 * {@code pageSize=200} 生效、{@code pageIndex} 翻页有效。
 *
 * <p><b>这是此前未尝试的一条路径</b>：{@link V2exJobsJobProvider} 的类注释记录了
 * 「BOSS / 智联 / 前程无忧 / 拉勾 / 实习僧 / 牛客 / 电鸭 均无公开 JSON 接口」的调研结论。
 * 本次补充的是「<b>大厂自建招聘官网</b>」——它同样满足「只用公开无认证接口」的合规红线，
 * 且数据是真实的在招岗位，不是种子快照。
 *
 * <p><b>字段取舍（不编造）</b>：
 * <ul>
 *   <li>腾讯不公开薪资与学历 → {@code salary} / {@code degree} 留空，不推算；</li>
 *   <li>无截止日期字段 → {@code deadline} 留空（项目红线：上游未提供的一律留空）；</li>
 *   <li>招聘类型按标题推断（含「实习」→ 实习，含「校招/校园」→ 秋招，其余 → 社招），
 *       这是<b>基于真实文本的分类</b>，不是编造字段；</li>
 *   <li>事业群（IEG / WXG / CSIG…）进 {@code tags}，行业留空交给 {@code JobClassifyService}。</li>
 * </ul>
 */
@Component
public class TencentCareersJobProvider extends AbstractOpenApiJobProvider {

    private static final Logger log = LoggerFactory.getLogger(TencentCareersJobProvider.class);

    private static final String PLATFORM = "腾讯招聘";

    /**
     * 单页 200 条（实测生效）。
     *
     * <p>P1-6（2026-10-21）：此前 {@code pageIndex=1} 被写死在 URL 里，于是**永远只抓第一页**
     * ——200/2287 ≈ 8.7% 的岗位能被看见，其余 2000+ 条从上线起就没进过库。
     * 现在按 {@link #pagesPerRefresh} 逐轮轮转页码。
     */
    private static final int PAGE_SIZE = 200;

    /** 取不到 {@code Data.Count} 时的保守页数上限（15 页 × 200 ≈ 3000 条，覆盖实测总量） */
    private static final int FALLBACK_MAX_PAGE = 15;

    private static final String BASE_URL =
            "https://careers.tencent.com/tencentcareer/api/post/Query";

    /** 页码游标：1..maxPage 循环推进（刷新线程单线程调用，仍用 Atomic 以防调度重叠） */
    private final java.util.concurrent.atomic.AtomicInteger pageCursor =
            new java.util.concurrent.atomic.AtomicInteger(1);

    /** 页数上限：首次响应后按 {@code Data.Count} 动态推导，岗位总数变化时自适应 */
    private volatile int maxPage = FALLBACK_MAX_PAGE;

    /**
     * 每轮拉取几页，默认 1。
     *
     * <p>为什么不默认更大：拉多页会拉长单轮耗时并增加上游压力，而 60 天窗口非常宽裕
     * ——12 轮 × 6h ≈ 3 天就能覆盖 2282 条，完全够用。
     * 想更快覆盖就调大 {@code app.job-agent.tencent.pages-per-refresh}，代价是单轮变慢。
     *
     * <p>字段初始化给 1 是必要的：单元测试直接 {@code new} 出来的实例不走 Spring 注入。
     *
     * <p>⚠️ 2026-10-29 修正：此前这里写的是 {@code app.jobs.*}，而 application.yml 的
     * 配置在 {@code app.job-agent.*} 下 —— 键名不匹配导致该配置与同名环境变量
     * <b>从未生效</b>（永远取默认 1），是一次典型的静默配置失效。
     */
    @org.springframework.beans.factory.annotation.Value("${app.job-agent.tencent.pages-per-refresh:1}")
    private int pagesPerRefresh = 1;

    public TencentCareersJobProvider(ObjectMapper objectMapper) {
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
        return buildUrl(1);
    }

    /**
     * 按游标轮转抓取（P1-6）。
     *
     * <p>每轮推进 {@code pagesPerRefresh} 页，到末页回绕到第 1 页。页数上限来自
     * 响应里的 {@code Data.Count}（{@code ceil(Count / 200)}），所以上游岗位总数增减时
     * 不需要改代码，也不会一直去请求不存在的页。
     */
    @Override
    public List<JobDto> fetch() {
        int pages = Math.max(1, pagesPerRefresh);
        List<JobDto> all = new ArrayList<>();
        for (int i = 0; i < pages; i++) {
            String url = buildUrl(nextPage());
            String body;
            try {
                body = fetchRaw(url);
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                log.warn("公开数据源 {} 拉取失败：{}", platform(), e.getMessage());
                throw new IllegalStateException("拉取失败：" + e.getMessage(), e);
            }
            JsonNode root;
            try {
                root = objectMapper.readTree(body);
            } catch (Exception e) {
                log.warn("公开数据源 {} 数据解析失败：{}", platform(), e.getMessage());
                throw new IllegalStateException("数据解析失败：" + e.getMessage(), e);
            }
            updateMaxPage(root);
            // 配额按**页**计算（200/页），翻页不放大单轮上限
            all.addAll(applyQuotaForSource(parse(root)));
        }
        return all;
    }

    /** 拼请求 URL（页码是唯一变量，便于单测直接断言） */
    static String buildUrl(int pageIndex) {
        return BASE_URL + "?pageIndex=" + pageIndex + "&pageSize=" + PAGE_SIZE + "&language=zh-cn";
    }

    /** 由岗位总数推导页数上限：{@code ceil(count / 200)}；count 非法时保持原值 */
    static int maxPageOf(long count) {
        if (count <= 0) {
            return FALLBACK_MAX_PAGE;
        }
        return Math.max(1, (int) Math.ceil(count / (double) PAGE_SIZE));
    }

    /** 当前页数上限（测试与诊断用） */
    int maxPage() {
        return maxPage;
    }

    /** 取下一页页码并推进游标（到末页回绕到 1） */
    private int nextPage() {
        return pageCursor.getAndUpdate(p -> p >= maxPage ? 1 : p + 1);
    }

    /** 从响应里读 {@code Data.Count} 并更新页数上限；缺失时保留当前值（不退回默认，避免抖动） */
    private void updateMaxPage(JsonNode root) {
        JsonNode countNode = root == null ? null : root.path("Data").path("Count");
        if (countNode == null || !countNode.isNumber()) {
            return;
        }
        maxPage = maxPageOf(countNode.asLong());
    }

    @Override
    protected List<JobDto> parse(JsonNode root) {
        List<JobDto> result = new ArrayList<>();
        JsonNode posts = root == null ? null : root.path("Data").path("Posts");
        if (posts == null || !posts.isArray()) {
            return result;
        }
        for (JsonNode node : posts) {
            // 上游明确标记下线的岗位直接跳过
            JsonNode valid = node.get("IsValid");
            if (valid != null && valid.isBoolean() && !valid.asBoolean()) {
                continue;
            }
            String postId = text(node, "PostId");
            String title = text(node, "RecruitPostName");
            // 无稳定 id 无法保证幂等 upsert，丢弃
            if (postId == null || title == null) {
                continue;
            }

            result.add(new JobDto(
                    clip("tx-" + postId, LEN_EXTERNAL_ID),
                    clip(title, LEN_TITLE),
                    "腾讯",
                    null,                                   // 行业未提供，交给分类服务
                    clip(text(node, "CategoryName"), 50),   // 产品/技术/设计… 与系统 jobType 取值一致
                    clip(text(node, "LocationName"), LEN_LOCATION),
                    null,                                   // 薪资未公开
                    null,                                   // 学历未提供
                    clip(text(node, "RequireWorkYearsName"), LEN_SALARY),
                    recruitTypeOf(title),
                    null,                                   // 截止日期未提供
                    clip(text(node, "PostURL"), LEN_URL),
                    plainText(text(node, "Responsibility"), DESC_MAX_LEN),
                    null,                                   // 无独立的「岗位要求」字段
                    clip(text(node, "BGName"), LEN_TAGS)    // 事业群作标签
            ));
        }
        return result;
    }

    /**
     * 按标题推断招聘类型。
     *
     * <p>只认标题里**明确写出来**的词，不做语义猜测——猜错会让用户在
     * 「秋招」「实习」筛选里看到不该出现的岗位，比不标更糟。
     */
    static String recruitTypeOf(String title) {
        String t = title == null ? "" : title;
        if (t.contains("实习")) {
            return "INTERN";
        }
        if (t.contains("校招") || t.contains("校园")) {
            return "AUTUMN";
        }
        return "SOCIAL";
    }
}
