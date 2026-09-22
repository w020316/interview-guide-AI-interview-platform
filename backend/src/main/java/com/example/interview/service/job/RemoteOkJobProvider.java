package com.example.interview.service.job;

import com.example.interview.config.JobAgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * RemoteOK 公开招聘数据源（v1.37.0）
 *
 * <p>接口：{@code https://remoteok.com/api}，公开无需鉴权，返回岗位数组（首个元素为
 * 站点 legal 声明，无 position 字段，天然被关键字校验过滤）。
 *
 * <p>数据特征：以全球远程的研发/设计/产品岗为主，字段含结构化薪资区间（年薪 USD），
 * 是三个公开源里信息密度最高的一个，因此作为「海外远程」主来源。
 */
@Component
public class RemoteOkJobProvider extends AbstractOpenApiJobProvider {

    private final JobAgentProperties properties;

    public RemoteOkJobProvider(ObjectMapper objectMapper, JobAgentProperties properties) {
        super(objectMapper);
        this.properties = properties;
    }

    @Override
    public String platform() {
        return "RemoteOK 全球远程";
    }

    @Override
    public boolean isEnabled() {
        return properties.isOpenApiEnabled();
    }

    @Override
    protected String endpoint() {
        return "https://remoteok.com/api";
    }

    @Override
    protected List<JobDto> parse(JsonNode root) {
        List<JobDto> result = new ArrayList<>();
        if (root == null || !root.isArray()) {
            return result;
        }
        for (JsonNode node : root) {
            String title = text(node, "position", "title");
            String company = text(node, "company");
            String rawId = text(node, "id", "slug");
            if (title == null || company == null || rawId == null) {
                continue;
            }
            String slug = text(node, "slug");
            String url = text(node, "apply_url", "url");
            if (url == null && slug != null) {
                url = "https://remoteok.com/remote-jobs/" + slug;
            }
            LocalDate posted = parseDatePrefix(text(node, "date"));

            result.add(new JobDto(
                    clip("rok-" + rawId, LEN_EXTERNAL_ID),
                    clip(title, LEN_TITLE),
                    clip(company, LEN_COMPANY),
                    "互联网",
                    jobTypeOf(node),
                    clip(locationOf(node), LEN_LOCATION),
                    clip(salaryOf(node), LEN_SALARY),
                    "不限",
                    "不限",
                    "SOCIAL",
                    deadlineFrom(posted, 60),
                    clip(url, LEN_URL),
                    desc(node),
                    null,
                    clip(joinArray(node, "tags"), LEN_TAGS)
            ));
        }
        return result;
    }

    /** 地点：上游为 Worldwide 时归一为「全球远程」，便于筛选与展示统一 */
    private static String locationOf(JsonNode node) {
        String loc = text(node, "location");
        if (loc == null || "worldwide".equalsIgnoreCase(loc) || "anywhere".equalsIgnoreCase(loc)) {
            return "全球远程";
        }
        return loc;
    }

    /** 薪资：把 salary_min / salary_max（年薪 USD）拼为可读区间 */
    private static String salaryOf(JsonNode node) {
        JsonNode min = node.get("salary_min");
        JsonNode max = node.get("salary_max");
        long lo = min != null && min.isNumber() ? min.asLong() : 0;
        long hi = max != null && max.isNumber() ? max.asLong() : 0;
        if (lo > 0 && hi > 0) {
            return "$" + (lo / 1000) + "k - $" + (hi / 1000) + "k / 年";
        }
        if (hi > 0) {
            return "最高 $" + (hi / 1000) + "k / 年";
        }
        return null;
    }

    /** 职位类型：上游无结构化字段，依据 tags + 标题关键词粗分类 */
    private static String jobTypeOf(JsonNode node) {
        String tags = joinArray(node, "tags");
        String title = text(node, "position", "title");
        String t = ((tags == null ? "" : tags) + " " + (title == null ? "" : title)).toLowerCase();
        if (matchAny(t, "market", "growth", "seo", "content", "brand")) return "市场";
        if (matchAny(t, "sales", "account exec", "business development")) return "销售";
        if (matchAny(t, "design", "ux", "ui ", "graphic")) return "设计";
        if (matchAny(t, "product manager", "product owner", "product lead")) return "产品";
        if (matchAny(t, "recruit", "talent", "human resources", "people ops")) return "职能";
        if (matchAny(t, "support", "customer success", "customer service")) return "客服";
        if (matchAny(t, "finance", "accounting", "controller")) return "金融";
        return "技术";
    }

    private static boolean matchAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }
}
