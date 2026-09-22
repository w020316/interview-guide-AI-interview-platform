package com.example.interview.service.job;

import com.example.interview.config.JobAgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Arbeitnow 公开招聘数据源（v1.37.0）
 *
 * <p>接口：{@code https://www.arbeitnow.com/api/job-board-api}，公开无需鉴权，
 * 岗位位于 {@code data} 数组。
 *
 * <p>数据特征：以德国/欧洲的本地与远程岗位为主，含明确的 remote 布尔标记，
 * 是三个公开源里唯一带「是否远程」结构化字段的，可作为欧洲方向的补充来源。
 */
@Component
public class ArbeitnowJobProvider extends AbstractOpenApiJobProvider {

    private final JobAgentProperties properties;

    public ArbeitnowJobProvider(ObjectMapper objectMapper, JobAgentProperties properties) {
        super(objectMapper);
        this.properties = properties;
    }

    @Override
    public String platform() {
        return "Arbeitnow 欧洲";
    }

    @Override
    public boolean isEnabled() {
        return properties.isOpenApiEnabled();
    }

    @Override
    protected String endpoint() {
        return "https://www.arbeitnow.com/api/job-board-api";
    }

    @Override
    protected List<JobDto> parse(JsonNode root) {
        List<JobDto> result = new ArrayList<>();
        JsonNode data = root == null ? null : root.get("data");
        if (data == null || !data.isArray()) {
            return result;
        }
        for (JsonNode node : data) {
            String title = text(node, "title");
            String company = text(node, "company_name");
            String rawId = text(node, "slug");
            if (title == null || company == null || rawId == null) {
                continue;
            }
            boolean remote = node.path("remote").asBoolean(false);
            LocalDate posted = fromEpochSecond(node.path("created_at").asLong(0));

            result.add(new JobDto(
                    clip("arb-" + rawId, LEN_EXTERNAL_ID),
                    clip(title, LEN_TITLE),
                    clip(company, LEN_COMPANY),
                    industryOf(node),
                    jobTypeOf(node),
                    clip(locationOf(node, remote), LEN_LOCATION),
                    null,
                    "不限",
                    "不限",
                    "SOCIAL",
                    deadlineFrom(posted, 60),
                    clip(text(node, "url"), LEN_URL),
                    desc(node),
                    null,
                    clip(tagsOf(node, remote), LEN_TAGS)
            ));
        }
        return result;
    }

    /** 地点：remote 为真时统一标注「欧洲远程」，否则保留城市名 */
    private static String locationOf(JsonNode node, boolean remote) {
        String loc = text(node, "location");
        if (remote) {
            return loc == null ? "欧洲远程" : loc + "（可远程）";
        }
        return loc == null ? "欧洲" : loc;
    }

    private static String industryOf(JsonNode node) {
        String t = low(node);
        if (matchAny(t, "developer", "software", "engineer", "data", "devops", "backend", "frontend")) return "互联网";
        if (matchAny(t, "design")) return "设计";
        if (matchAny(t, "market", "seo", "content")) return "传媒";
        if (matchAny(t, "sales", "account")) return "销售";
        if (matchAny(t, "finance", "accounting", "tax")) return "金融";
        if (matchAny(t, "nurse", "doctor", "health", "medical", "pflege")) return "医疗";
        if (matchAny(t, "teach", "education", "teacher")) return "教育";
        if (matchAny(t, "logistic", "supply", "warehouse", "driver")) return "物流";
        if (matchAny(t, "engineer", "mechanic", "electric", "production")) return "制造";
        return "其他";
    }

    private static String jobTypeOf(JsonNode node) {
        String t = low(node);
        if (matchAny(t, "market", "seo", "content")) return "市场";
        if (matchAny(t, "sales", "account")) return "销售";
        if (matchAny(t, "design", "ux", "ui")) return "设计";
        if (matchAny(t, "product")) return "产品";
        if (matchAny(t, "human resources", "recruit", "talent")) return "职能";
        if (matchAny(t, "support", "customer")) return "客服";
        if (matchAny(t, "finance", "accounting")) return "金融";
        if (matchAny(t, "logistic", "supply", "warehouse", "driver")) return "供应链";
        return "技术";
    }

    /** 标签：tags + job_types + remote 标记合并 */
    private static String tagsOf(JsonNode node, boolean remote) {
        List<String> parts = new ArrayList<>();
        String tags = joinArray(node, "tags");
        if (tags != null) parts.add(tags);
        String jobTypes = joinArray(node, "job_types");
        if (jobTypes != null) parts.add(jobTypes);
        if (remote) parts.add("远程");
        return parts.isEmpty() ? null : String.join(",", parts);
    }

    /** 标题 + 标签的小写合并串，供关键词粗分类复用 */
    private static String low(JsonNode node) {
        String title = text(node, "title");
        String tags = joinArray(node, "tags");
        return ((title == null ? "" : title) + " " + (tags == null ? "" : tags)).toLowerCase();
    }

    private static boolean matchAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }
}
