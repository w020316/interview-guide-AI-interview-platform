package com.example.interview.service.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 亚马逊中国（在华外企，国内实时数据源，v1.81.0）
 *
 * <p><b>为什么加这个源</b>：用户诉求是「国内岗位要多，且包括在国内的外企」。
 * 此前国内只有 {@link TencentCareersJobProvider} 与 V2EX 两个活源，
 * 且没有任何一家在华外企的岗位——外企招聘页大多走自有系统、无公开接口。
 *
 * <p><b>接口</b>：{@code GET https://www.amazon.jobs/en/search.json}
 * —— 公开、无需 Key、无需登录。
 * <b>关键参数是 {@code country=CHN}</b>：实测 {@code loc_query=China} 与
 * {@code normalized_location=...} 都**不生效**（返回 hits=10000 的全量美国岗位），
 * 只有 {@code country=CHN} 才返回真实的中国岗位（hits=241，location 形如 {@code CN, 31, Shanghai}）。
 *
 * <p><b>批量上限</b>：{@code result_limit=50} 稳定（约 346KB）；
 * {@code result_limit=100} 实测响应在 390KB 处被截断、JSON 解析失败，故取 50。
 *
 * <p><b>其他外企的实测结论（勿重复调研）</b>：
 * <ul>
 *   <li>微软 {@code gcsservices.careers.microsoft.com} —— 连接超时；</li>
 *   <li>苹果 {@code jobs.apple.com/api/v1/search} —— 401，需要认证；</li>
 *   <li>特斯拉 {@code tesla.com/cua-api/apps/careers/state} —— 403；</li>
 *   <li>SAP {@code jobs.sap.com} —— 403；</li>
 *   <li>英特尔 {@code jobs.intel.com/api/jobs} —— 301 重定向到需登录的页面。</li>
 * </ul>
 * 这些走的是自有系统 + 反爬，不符合「只用公开无认证接口」的红线。
 *
 * <p><b>字段取舍（不编造）</b>：亚马逊不公开薪资与学历要求 → 留空；
 * 无截止日期 → 留空；岗位类别是英文（{@code Systems, Quality, & Security Engineering}），
 * 与系统的中文 jobType 取值不同源，故 <b>不硬映射</b>，留空交给 {@code JobClassifyService}，
 * 原始英文类别进 {@code tags} 保留信息。
 */
@Component
public class AmazonChinaJobProvider extends AbstractOpenApiJobProvider {

    private static final String PLATFORM = "亚马逊中国";

    /** 实测稳定上限：50 条约 346KB；100 条会被截断 */
    private static final int PAGE_SIZE = 50;

    private static final String ENDPOINT =
            "https://www.amazon.jobs/en/search.json?country=CHN&result_limit=" + PAGE_SIZE;

    public AmazonChinaJobProvider(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    public String platform() {
        return PLATFORM;
    }

    /** 岗位地点在中国 → 属于国内视图，不进「海外远程」分栏 */
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
    protected List<JobDto> parse(JsonNode root) {
        List<JobDto> result = new ArrayList<>();
        JsonNode jobs = root == null ? null : root.get("jobs");
        if (jobs == null || !jobs.isArray()) {
            return result;
        }
        for (JsonNode node : jobs) {
            String id = text(node, "id_icims");
            String title = text(node, "title");
            // 无稳定 id 无法保证幂等 upsert，丢弃
            if (id == null || title == null) {
                continue;
            }
            String path = text(node, "job_path");
            String requirements = joinNonBlank(
                    text(node, "basic_qualifications"),
                    text(node, "preferred_qualifications"));

            result.add(new JobDto(
                    clip("amz-" + id, LEN_EXTERNAL_ID),
                    clip(title, LEN_TITLE),
                    clip(text(node, "company_name"), LEN_COMPANY),
                    null,                                   // 英文类别与中文行业不同源，交给分类服务
                    null,                                   // 同上：不硬映射 jobType
                    clip(cleanLocation(text(node, "location")), LEN_LOCATION),
                    null,                                   // 薪资未公开
                    null,                                   // 学历未提供
                    null,                                   // 年限在 qualifications 里，非结构化，不硬抽
                    "SOCIAL",                               // 亚马逊该接口以社招为主
                    null,                                   // 截止日期未提供
                    clip(path == null ? null : "https://www.amazon.jobs" + path, LEN_URL),
                    plainText(text(node, "description"), DESC_MAX_LEN),
                    plainText(requirements, DESC_MAX_LEN),
                    clip(text(node, "job_category"), LEN_TAGS)
            ));
        }
        return result;
    }

    /**
     * 地点清洗：{@code "CN, 31, Shanghai"} → {@code "Shanghai"}。
     *
     * <p>只取最后一段（城市），不翻译成中文——翻译需要维护城市映射表，
     * 一旦漏词就会出现中英混排；保留原文地名对求职者同样可读。
     */
    static String cleanLocation(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split(",");
        String city = parts[parts.length - 1].trim();
        return city.isEmpty() ? null : city;
    }

    /** 两段要求拼成一段，都为空时返回 null（不产生空字符串字段） */
    private static String joinNonBlank(String a, String b) {
        boolean hasA = a != null && !a.isBlank();
        boolean hasB = b != null && !b.isBlank();
        if (hasA && hasB) {
            return a + "\n\n" + b;
        }
        if (hasA) {
            return a;
        }
        return hasB ? b : null;
    }
}
