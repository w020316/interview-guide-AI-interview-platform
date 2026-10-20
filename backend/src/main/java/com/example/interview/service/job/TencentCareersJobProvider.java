package com.example.interview.service.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final String PLATFORM = "腾讯招聘";

    /**
     * 单页 200 条（实测生效），配合 6 小时一轮的默认刷新间隔。
     * 全量 2287 条约需 2 天累积——幂等 upsert 保证重复拉取不会产生重复岗位。
     */
    private static final int PAGE_SIZE = 200;

    private static final String ENDPOINT =
            "https://careers.tencent.com/tencentcareer/api/post/Query"
                    + "?pageIndex=1&pageSize=" + PAGE_SIZE + "&language=zh-cn";

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
        return ENDPOINT;
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
