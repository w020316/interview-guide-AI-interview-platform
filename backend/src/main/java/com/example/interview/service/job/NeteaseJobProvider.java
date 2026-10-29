package com.example.interview.service.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 网易招聘（国内实时数据源，v1.87.0）
 *
 * <p><b>接口</b>：{@code POST https://hr.163.com/api/hr163/position/queryPage}
 * —— 网易招聘官网自身的公开接口，无需 Key / Cookie。实测 {@code total=2729}，
 * {@code pageSize=50} 生效（55 页），{@code currentPage} 翻页有效。
 *
 * <p><b>覆盖范围</b>：该接口不只有游戏业务，样本里同时出现网易云音乐、网易严选、
 * 雷火 / 互娱事业群等，因此 {@code companyName} 取每条岗位的 {@code productName}
 * （如「网易云音乐」），而不是笼统写「网易」。
 *
 * <p><b>字段取舍（不编造）</b>：
 * <ul>
 *   <li>上游不公开薪资 → 留空；</li>
 *   <li>{@code reqEducationName} / {@code reqWorkYearsName} 是真实结构化要求，
 *       如实透传（由 {@code JobFieldNormalizer} 归一，如「其他」→「不限」）；</li>
 *   <li>{@code updateTime} 是「最近更新时间」而非发布时间 → 不据此推算任何日期，
 *       截止日期一律留空；</li>
 *   <li>{@code workType}：实测 {@code 1}=实习（样本全部带「实习生」）、
 *       {@code 0}=社招、{@code 2}=派遣/外包；只有实习能确定，其余按标题兜底；</li>
 *   <li>{@code firstPostTypeName} 是游戏行业专有类别（游戏程序 / 游戏策划 / 游戏艺术…），
 *       与系统 jobType 枚举不同源，不硬映射，原样进 tags 并交给分类服务。</li>
 * </ul>
 */
@Component
public class NeteaseJobProvider extends AbstractOpenApiJobProvider {

    private static final Logger log = LoggerFactory.getLogger(NeteaseJobProvider.class);

    private static final String PLATFORM = "网易招聘";

    /** 实测 50 条/页稳定（55 页 × 50 ≈ 2729） */
    private static final int PAGE_SIZE = 50;

    /** 取不到 {@code data.pages} 时的保守页数上限（10 页 × 50 = 500 条） */
    private static final int FALLBACK_MAX_PAGE = 10;

    private static final String ENDPOINT =
            "https://hr.163.com/api/hr163/position/queryPage";

    private static final String REFERER = "https://hr.163.com/position/list.html";

    private static final String DETAIL_URL = "https://hr.163.com/position/detail.html?id=";

    private final java.util.concurrent.atomic.AtomicInteger pageCursor =
            new java.util.concurrent.atomic.AtomicInteger(1);

    private volatile int maxPage = FALLBACK_MAX_PAGE;

    /** 每轮拉取几页，默认 1（55 页 × 6h ≈ 14 天覆盖全量） */
    @org.springframework.beans.factory.annotation.Value("${app.job-agent.netease.pages-per-refresh:1}")
    private int pagesPerRefresh = 1;

    public NeteaseJobProvider(ObjectMapper objectMapper) {
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

    /** 请求体（页码是唯一变量） */
    static String buildBody(int pageNo) {
        return "{\"currentPage\":" + pageNo + ",\"pageSize\":" + PAGE_SIZE + "}";
    }

    /** 页数即响应里的 {@code data.pages}；非法值回退保守上限 */
    static int maxPageOf(long pages) {
        if (pages <= 0) {
            return FALLBACK_MAX_PAGE;
        }
        return (int) Math.min(pages, Integer.MAX_VALUE);
    }

    /** 当前页数上限（测试与诊断用） */
    int maxPage() {
        return maxPage;
    }

    private int nextPage() {
        return pageCursor.getAndUpdate(p -> p >= maxPage ? 1 : p + 1);
    }

    /** 从响应读 {@code data.pages}；缺失/非法时保留原上限 */
    private void updateMaxPage(JsonNode root) {
        JsonNode pages = root == null ? null : root.path("data").path("pages");
        if (pages == null || !pages.isNumber()) {
            return;
        }
        maxPage = maxPageOf(pages.asLong());
    }

    @Override
    protected List<JobDto> parse(JsonNode root) {
        List<JobDto> result = new ArrayList<>();
        JsonNode list = root == null ? null : root.path("data").path("list");
        if (list == null || !list.isArray()) {
            return result;
        }
        for (JsonNode node : list) {
            String id = text(node, "id");
            String title = text(node, "name");
            // 无稳定 id 无法保证幂等 upsert，丢弃
            if (id == null || title == null) {
                continue;
            }
            String product = text(node, "productName");
            String category = text(node, "firstPostTypeName");
            String department = text(node, "firstDepName");

            result.add(new JobDto(
                    clip("ne-" + id, LEN_EXTERNAL_ID),
                    clip(title, LEN_TITLE),
                    clip(product == null ? "网易" : product, LEN_COMPANY),
                    null,                                   // 行业未提供，交给分类服务
                    null,                                   // 游戏专有类别，不硬映射 jobType
                    clip(joinArray(node, "workPlaceNameList"), LEN_LOCATION),
                    null,                                   // 薪资未公开
                    clip(text(node, "reqEducationName"), 50),
                    clip(text(node, "reqWorkYearsName"), LEN_SALARY),
                    recruitTypeOf(text(node, "workType"), title),
                    null,                                   // 截止日期未提供
                    clip(DETAIL_URL + id, LEN_URL),
                    plainText(text(node, "description"), DESC_MAX_LEN),
                    plainText(text(node, "requirement"), DESC_MAX_LEN),
                    clip(joinNonBlank(category, department), LEN_TAGS)
            ));
        }
        return result;
    }

    /**
     * 招聘类型：{@code workType=1} 是实习（实测样本全部带「实习生」）；
     * 其余（0 社招 / 2 派遣）按标题兜底，只认明确写出的词。
     */
    static String recruitTypeOf(String workType, String title) {
        if ("1".equals(workType)) {
            return "INTERN";
        }
        String t = title == null ? "" : title;
        if (t.contains("实习")) {
            return "INTERN";
        }
        if (t.contains("校招") || t.contains("校园")) {
            return "AUTUMN";
        }
        return "SOCIAL";
    }

    private static String joinNonBlank(String a, String b) {
        boolean hasA = a != null && !a.isBlank();
        boolean hasB = b != null && !b.isBlank();
        if (hasA && hasB) {
            return a + "," + b;
        }
        return hasA ? a : (hasB ? b : null);
    }
}
