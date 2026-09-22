package com.example.interview.service.job;

import com.example.interview.config.JobAgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Jobicy 公开招聘数据源（v1.38.0）
 *
 * <p>接口：{@code https://jobicy.com/api/v2/remote-jobs?count=50}，公开无需鉴权，
 * 岗位位于 {@code jobs} 数组。
 *
 * <p>数据特征：相比 RemoteOK 只给自由标签，Jobicy 提供结构化的
 * {@code jobIndustry}（行业数组）、{@code jobType}（用工类型数组）、
 * {@code jobLevel}（职级）与年薪区间，字段语义更清晰，解析歧义更少。
 */
@Component
public class JobicyJobProvider extends AbstractOpenApiJobProvider {

    private final JobAgentProperties properties;

    public JobicyJobProvider(ObjectMapper objectMapper, JobAgentProperties properties) {
        super(objectMapper);
        this.properties = properties;
    }

    @Override
    public String platform() {
        return "Jobicy 全球远程";
    }

    @Override
    public boolean isEnabled() {
        return properties.isOpenApiEnabled();
    }

    @Override
    protected String endpoint() {
        return "https://jobicy.com/api/v2/remote-jobs?count=50";
    }

    @Override
    protected List<JobDto> parse(JsonNode root) {
        List<JobDto> result = new ArrayList<>();
        JsonNode jobs = root == null ? null : root.get("jobs");
        if (jobs == null || !jobs.isArray()) {
            return result;
        }
        for (JsonNode node : jobs) {
            String title = text(node, "jobTitle", "title");
            String company = text(node, "companyName", "company");
            // id 缺失时用 jobSlug 兜底，两者都没有才丢弃（无法保证幂等 upsert）
            String rawId = text(node, "id", "jobSlug");
            if (title == null || company == null || rawId == null) {
                continue;
            }
            LocalDate posted = parseDatePrefix(text(node, "pubDate"));

            result.add(new JobDto(
                    clip("jby-" + rawId, LEN_EXTERNAL_ID),
                    clip(title, LEN_TITLE),
                    clip(company, LEN_COMPANY),
                    industryOf(node),
                    jobTypeOf(node),
                    clip(locationOf(node), LEN_LOCATION),
                    clip(salaryOf(node), LEN_SALARY),
                    "不限",
                    "不限",
                    "SOCIAL",
                    deadlineFrom(posted, 60),
                    clip(text(node, "url"), LEN_URL),
                    plainText(text(node, "jobDescription", "jobExcerpt"), 1200),
                    null,
                    clip(tagsOf(node), LEN_TAGS)
            ));
        }
        return result;
    }

    /** 地点：jobGeo 为 Anywhere/Worldwide 时归一为「全球远程」 */
    private static String locationOf(JsonNode node) {
        String geo = text(node, "jobGeo");
        if (geo == null) return "全球远程";
        String g = geo.toLowerCase();
        if (g.contains("anywhere") || g.contains("worldwide")) return "全球远程";
        return geo + "（远程）";
    }

    /** 薪资：上游是字符串型年薪区间 + 币种，拼成可读区间 */
    private static String salaryOf(JsonNode node) {
        String min = text(node, "annualSalaryMin");
        String max = text(node, "annualSalaryMax");
        String currency = text(node, "salaryCurrency");
        String symbol = switch (currency == null ? "" : currency.toUpperCase()) {
            case "USD" -> "$";
            case "EUR" -> "€";
            case "GBP" -> "£";
            case "CNY", "RMB" -> "¥";
            default -> "";
        };
        if (min != null && max != null) {
            return symbol + min + " - " + symbol + max + " / 年";
        }
        if (max != null) {
            return "最高 " + symbol + max + " / 年";
        }
        return null;
    }

    /** 标签：用工类型（full-time 等）+ 职级 */
    private static String tagsOf(JsonNode node) {
        List<String> parts = new ArrayList<>();
        String types = joinArray(node, "jobType");
        if (types != null) parts.add(types);
        String level = text(node, "jobLevel");
        if (level != null) parts.add(level);
        return parts.isEmpty() ? null : String.join(",", parts);
    }

    private static String industryOf(JsonNode node) {
        String t = lowerText(node, "jobIndustry");
        if (matchAny(t, "engineer", "developer", "software", "data", "devops", "qa", "it ")) return "互联网";
        if (matchAny(t, "design", "creative", "artist")) return "设计";
        if (matchAny(t, "market", "seo", "content", "copywrit", "social media")) return "传媒";
        if (matchAny(t, "sales", "business", "account exec")) return "销售";
        if (matchAny(t, "finance", "accounting", "legal")) return "金融";
        if (matchAny(t, "health", "medical", "nurse")) return "医疗";
        if (matchAny(t, "teach", "education", "tutor")) return "教育";
        if (matchAny(t, "support", "customer")) return "客服";
        if (matchAny(t, "hr", "human resources", "recruit")) return "人力资源";
        return "互联网";
    }

    private static String jobTypeOf(JsonNode node) {
        String t = lowerText(node, "jobIndustry");
        if (matchAny(t, "market", "seo", "content", "copywrit")) return "市场";
        if (matchAny(t, "sales", "business development", "account exec")) return "销售";
        if (matchAny(t, "design", "ux", "ui", "creative")) return "设计";
        if (matchAny(t, "product manager", "product owner")) return "产品";
        if (matchAny(t, "human resources", "recruit", "talent")) return "职能";
        if (matchAny(t, "customer", "support")) return "客服";
        if (matchAny(t, "finance", "accounting", "legal")) return "金融";
        if (matchAny(t, "project management", "operations")) return "职能";
        return "技术";
    }

    /** 行业数组 + 标题的小写合并串，供关键词粗分类复用（joinArray 可能为 null，需拼接安全） */
    private static String lowerText(JsonNode node, String arrayKey) {
        String joined = joinArray(node, arrayKey);
        String title = text(node, "jobTitle", "title");
        return ((joined == null ? "" : joined) + " " + (title == null ? "" : title)).toLowerCase();
    }

    private static boolean matchAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }
}
