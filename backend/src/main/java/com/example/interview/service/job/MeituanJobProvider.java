package com.example.interview.service.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 美团招聘（国内实时数据源，v1.87.0）
 *
 * <p><b>为什么加这个源</b>：国内实时源此前只有腾讯、亚马逊中国、V2EX 三个，
 * 其余岗位仍来自静态种子。2026-10-29 复测各大厂招聘官网接口后，美团是少数
 * 仍「匿名、无鉴权、结构化 JSON、可翻页」的一家（其余见类尾的实测结论）。
 *
 * <p><b>接口</b>：{@code POST https://zhaopin.meituan.com/api/official/job/getJobList}
 * —— 公开页面自身调用的接口，无需 Key / Cookie。实测 {@code totalCount=3282}，
 * {@code pageSize=50} 生效（66 页），{@code pageNo} 翻页有效。
 *
 * <p><b>字段取舍（不编造）</b>：
 * <ul>
 *   <li>上游不公开薪资、学历、截止日期 → 一律留空；</li>
 *   <li>{@code workYear} 实测 200 条样本全为 null → 经验留空，交给前端显示「不限」；</li>
 *   <li>{@code refreshTime} 是「最近刷新时间」（样本跨度 2026-08 ~ 2026-10），
 *       <b>不是发布时间</b>，故不据此推算任何日期；</li>
 *   <li>{@code jobFamily} 是真实中文类别（技术类 / 产品类 / 运营类…），
 *       只做**词面等价**映射到系统 jobType 枚举，无法等价的（零售类 / 商业分析类 /
 *       销售客服类）留空交给分类服务，不硬塞；</li>
 *   <li>招聘类型按标题推断（含「实习」→ 实习，含「校招/校园」→ 秋招，其余社招）。</li>
 * </ul>
 *
 * <p><b>2026-10-29 其他大厂接口实测（勿重复调研）</b>：字节跳动 405 / 百度 need-login /
 * 华为 404 / 京东 NotLogin / 小米 404 / 阿里 403 / 国家大学生就业服务平台跳登录 /
 * 国聘 405 / 快手·小红书·滴滴·携程·B站·拼多多 全部 404 或 403。
 */
@Component
public class MeituanJobProvider extends AbstractOpenApiJobProvider {

    private static final Logger log = LoggerFactory.getLogger(MeituanJobProvider.class);

    private static final String PLATFORM = "美团招聘";

    /** 实测 50 条/页稳定（66 页 × 50 ≈ 3282） */
    private static final int PAGE_SIZE = 50;

    /** 取不到 {@code totalCount} 时的保守页数上限（10 页 × 50 = 500 条） */
    private static final int FALLBACK_MAX_PAGE = 10;

    private static final String ENDPOINT =
            "https://zhaopin.meituan.com/api/official/job/getJobList";

    private static final String REFERER = "https://zhaopin.meituan.com/web/position";

    private static final String DETAIL_URL =
            "https://zhaopin.meituan.com/web/position/detail?jobUnionId=";

    /** 页码游标：1..maxPage 循环推进 */
    private final java.util.concurrent.atomic.AtomicInteger pageCursor =
            new java.util.concurrent.atomic.AtomicInteger(1);

    /** 页数上限：由 {@code data.page.totalCount} 推导 */
    private volatile int maxPage = FALLBACK_MAX_PAGE;

    /**
     * 每轮拉取几页，默认 1。
     * 66 页 → 1 页/轮 × 6h ≈ 16 天覆盖全量，远小于 60 天的陈旧清理窗口。
     * 字段初始化给 1：单元测试直接 new 实例，不走 Spring 注入。
     */
    @org.springframework.beans.factory.annotation.Value("${app.job-agent.meituan.pages-per-refresh:1}")
    private int pagesPerRefresh = 1;

    public MeituanJobProvider(ObjectMapper objectMapper) {
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
        return ENDPOINT;
    }

    @Override
    public List<JobDto> fetch() {
        int pages = Math.max(1, pagesPerRefresh);
        List<JobDto> all = new ArrayList<>();
        for (int i = 0; i < pages; i++) {
            String body = fetchRawPost(ENDPOINT, buildBody(nextPage()), REFERER);
            JsonNode root;
            try {
                root = objectMapper.readTree(body);
            } catch (Exception e) {
                log.warn("公开数据源 {} 数据解析失败：{}", platform(), e.getMessage());
                throw new IllegalStateException("数据解析失败：" + e.getMessage(), e);
            }
            updateMaxPage(root);
            all.addAll(applyQuotaForSource(parse(root)));
        }
        return all;
    }

    /** 请求体（页码是唯一变量，便于单测断言） */
    static String buildBody(int pageNo) {
        return "{\"jobShareType\":\"1\",\"keyword\":\"\",\"page\":{\"pageNo\":" + pageNo
                + ",\"pageSize\":" + PAGE_SIZE + "}}";
    }

    /** 由岗位总数推导页数：{@code ceil(total / 50)}；非法值回退保守上限 */
    static int maxPageOf(long totalCount) {
        if (totalCount <= 0) {
            return FALLBACK_MAX_PAGE;
        }
        return Math.max(1, (int) Math.ceil(totalCount / (double) PAGE_SIZE));
    }

    /** 当前页数上限（测试与诊断用） */
    int maxPage() {
        return maxPage;
    }

    /** 取下一页页码并推进游标（末页回绕到 1） */
    private int nextPage() {
        return pageCursor.getAndUpdate(p -> p >= maxPage ? 1 : p + 1);
    }

    /** 从响应读 {@code data.page.totalCount}；缺失/非法时保留原上限，避免抖动 */
    private void updateMaxPage(JsonNode root) {
        JsonNode total = root == null ? null : root.path("data").path("page").path("totalCount");
        if (total == null || !total.isNumber()) {
            return;
        }
        maxPage = maxPageOf(total.asLong());
    }

    @Override
    protected List<JobDto> parse(JsonNode root) {
        List<JobDto> result = new ArrayList<>();
        JsonNode list = root == null ? null : root.path("data").path("list");
        if (list == null || !list.isArray()) {
            return result;
        }
        for (JsonNode node : list) {
            String id = text(node, "jobUnionId");
            String title = text(node, "name");
            // 无稳定 id 无法保证幂等 upsert，丢弃
            if (id == null || title == null) {
                continue;
            }
            String jobFamily = text(node, "jobFamily");

            result.add(new JobDto(
                    clip("mt-" + id, LEN_EXTERNAL_ID),
                    clip(title, LEN_TITLE),
                    "美团",
                    null,                                   // 行业未提供，交给分类服务
                    jobTypeOf(jobFamily),
                    clip(cityNames(node.get("cityList")), LEN_LOCATION),
                    null,                                   // 薪资未公开
                    null,                                   // 学历未提供
                    clip(text(node, "workYear"), LEN_SALARY), // 实测为 null，如实透传
                    recruitTypeOf(title),
                    null,                                   // 截止日期未提供
                    clip(DETAIL_URL + id, LEN_URL),
                    plainText(text(node, "jobDuty"), DESC_MAX_LEN),
                    plainText(text(node, "jobRequirement"), DESC_MAX_LEN),
                    clip(joinNonBlank(jobFamily, text(node, "jobFamilyGroup")), LEN_TAGS)
            ));
        }
        return result;
    }

    /**
     * 岗位类别 → 系统 jobType（技术/产品/运营/设计/市场/职能/金融）。
     *
     * <p>只映射**词面等价**的类别；「零售类」「商业分析类」「销售、客服与支持类」
     * 与任一系统类别都不等价，返回 null 由分类服务判断，而不是硬塞一个近似值。
     */
    static String jobTypeOf(String jobFamily) {
        if (jobFamily == null || jobFamily.isBlank()) {
            return null;
        }
        String f = jobFamily.trim();
        if (f.startsWith("技术")) return "技术";
        if (f.startsWith("产品")) return "产品";
        if (f.startsWith("运营")) return "运营";
        if (f.startsWith("设计")) return "设计";
        if (f.startsWith("市场营销") || f.startsWith("市场")) return "市场";
        if (f.startsWith("职能")) return "职能";
        if (f.startsWith("金融")) return "金融";
        return null;
    }

    /** 招聘类型：只认标题里明确写出的词，不做语义猜测 */
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

    /** {@code cityList[].name} → 「北京市、上海市」；最多 3 个，去重 */
    static String cityNames(JsonNode cities) {
        if (cities == null || !cities.isArray() || cities.isEmpty()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        for (JsonNode city : cities) {
            String name = city.path("name").asText("").trim();
            if (!name.isBlank() && !names.contains(name)) {
                names.add(name);
            }
            if (names.size() >= 3) {
                break;
            }
        }
        return names.isEmpty() ? null : String.join("、", names);
    }

    /** 两段标签拼成一段；都为空返回 null */
    private static String joinNonBlank(String a, String b) {
        boolean hasA = a != null && !a.isBlank();
        boolean hasB = b != null && !b.isBlank();
        if (hasA && hasB) {
            return a + "," + b;
        }
        return hasA ? a : (hasB ? b : null);
    }
}
