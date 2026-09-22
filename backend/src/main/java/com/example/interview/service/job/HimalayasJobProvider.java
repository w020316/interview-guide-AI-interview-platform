package com.example.interview.service.job;

import com.example.interview.config.JobAgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Himalayas 公开招聘数据源（v1.38.0）
 *
 * <p>接口：{@code https://himalayas.app/jobs/api?limit=100}，公开无需鉴权，
 * 岗位位于 {@code jobs} 数组。
 *
 * <p>数据特征：是目前接入的四个海外源里**唯一提供到期时间（expiryDate）**的，
 * 因此优先采用它作为截止日期，而不是像其他源那样按发帖日 + 60 天推算——
 * 这让「临期优先」排序和过期自动下架更贴近上游真实意图。
 */
@Component
public class HimalayasJobProvider extends AbstractOpenApiJobProvider {

    private final JobAgentProperties properties;

    public HimalayasJobProvider(ObjectMapper objectMapper, JobAgentProperties properties) {
        super(objectMapper);
        this.properties = properties;
    }

    @Override
    public String platform() {
        return "Himalayas 全球远程";
    }

    @Override
    public boolean isEnabled() {
        return properties.isOpenApiEnabled();
    }

    @Override
    protected String endpoint() {
        return "https://himalayas.app/jobs/api?limit=100";
    }

    @Override
    protected List<JobDto> parse(JsonNode root) {
        List<JobDto> result = new ArrayList<>();
        JsonNode jobs = root == null ? null : root.get("jobs");
        if (jobs == null || !jobs.isArray()) {
            return result;
        }
        for (JsonNode node : jobs) {
            String title = text(node, "title");
            String company = text(node, "companyName");
            String idPart = externalIdPart(node);
            if (title == null || company == null || idPart == null) {
                continue;
            }
            LocalDate posted = fromEpochSecond(node.path("pubDate").asLong(0));
            LocalDate expiry = fromEpochSecond(node.path("expiryDate").asLong(0));

            result.add(new JobDto(
                    clip("hml-" + company + "-" + idPart, LEN_EXTERNAL_ID),
                    clip(title, LEN_TITLE),
                    clip(company, LEN_COMPANY),
                    industryOf(node),
                    jobTypeOf(node),
                    clip(locationOf(node), LEN_LOCATION),
                    clip(salaryOf(node), LEN_SALARY),
                    "不限",
                    "不限",
                    "SOCIAL",
                    // 上游给了到期时间就用它；没给才按发帖日 + 60 天推算
                    expiry != null ? expiry : deadlineFrom(posted, 60),
                    clip(text(node, "applicationLink", "guid"), LEN_URL),
                    plainText(text(node, "description", "excerpt"), 1200),
                    null,
                    clip(tagsOf(node), LEN_TAGS)
            ));
        }
        return result;
    }

    /**
     * 从 guid / 申请链接里取一个稳定短标识。
     *
     * <p>上游的 {@code guid} 是完整 URL（形如
     * {@code https://himalayas.app/companies/acme/jobs/senior-engineer}），
     * 直接当 externalId 会超 128 字符列上限，且前端展示也不好看，
     * 因此取末段路径。末段单独用仍有跨公司重名风险，调用方会再拼上公司名。
     */
    private static String externalIdPart(JsonNode node) {
        for (String key : List.of("guid", "applicationLink")) {
            String raw = text(node, key);
            if (raw == null) continue;
            String trimmed = raw.replaceAll("/+$", "");
            int slash = trimmed.lastIndexOf('/');
            String tail = slash >= 0 && slash < trimmed.length() - 1 ? trimmed.substring(slash + 1) : trimmed;
            if (!tail.isBlank()) {
                return clip(tail, 80);
            }
        }
        // 两个链接都缺失时用标题兜底：调用方还会拼上公司名，组合后仍能保证
        // 同一岗位每轮刷新得到相同 externalId（幂等 upsert 的前提）
        String title = text(node, "title");
        return title == null ? null : clip(title, 60);
    }

    /** 地点：locationRestrictions 为 Worldwide 或为空时归为全球远程 */
    private static String locationOf(JsonNode node) {
        String restrictions = joinArray(node, "locationRestrictions");
        if (restrictions == null) return "全球远程";
        String r = restrictions.toLowerCase();
        if (r.contains("worldwide") || r.contains("anywhere")) return "全球远程";
        return restrictions + "（远程）";
    }

    /** 薪资：minSalary / maxSalary / currency，单位与上游一致（通常为年薪） */
    private static String salaryOf(JsonNode node) {
        String min = numberText(node, "minSalary");
        String max = numberText(node, "maxSalary");
        String currency = text(node, "currency");
        String symbol = switch (currency == null ? "" : currency.toUpperCase()) {
            case "USD" -> "$";
            case "EUR" -> "€";
            case "GBP" -> "£";
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

    /** 标签：用工类型 + 职级 + 分类 */
    private static String tagsOf(JsonNode node) {
        List<String> parts = new ArrayList<>();
        String employment = text(node, "employmentType");
        if (employment != null) parts.add(employment);
        String seniority = joinArray(node, "seniority");
        if (seniority != null) parts.add(seniority);
        String categories = joinArray(node, "categories");
        if (categories != null) parts.add(categories);
        return parts.isEmpty() ? null : String.join(",", parts);
    }

    private static String industryOf(JsonNode node) {
        String t = lowerText(node);
        if (matchAny(t, "engineer", "developer", "software", "data", "devops", "qa")) return "互联网";
        if (matchAny(t, "design", "creative", "ux")) return "设计";
        if (matchAny(t, "market", "seo", "content", "copywrit", "social media")) return "传媒";
        if (matchAny(t, "sales", "business development", "account exec")) return "销售";
        if (matchAny(t, "finance", "accounting", "legal")) return "金融";
        if (matchAny(t, "health", "medical", "nurse")) return "医疗";
        if (matchAny(t, "teach", "education", "tutor")) return "教育";
        if (matchAny(t, "support", "customer")) return "客服";
        if (matchAny(t, "recruit", "human resources", "talent")) return "人力资源";
        return "互联网";
    }

    private static String jobTypeOf(JsonNode node) {
        String t = lowerText(node);
        if (matchAny(t, "market", "seo", "content", "copywrit")) return "市场";
        if (matchAny(t, "sales", "business development", "account exec")) return "销售";
        if (matchAny(t, "design", "ux", "ui ", "creative")) return "设计";
        if (matchAny(t, "product manager", "product owner", "product design")) return "产品";
        if (matchAny(t, "recruit", "human resources", "talent")) return "职能";
        if (matchAny(t, "customer", "support")) return "客服";
        if (matchAny(t, "finance", "accounting", "legal")) return "金融";
        if (matchAny(t, "operations", "project management")) return "职能";
        return "技术";
    }

    /** 分类数组 + 职级 + 标题的小写合并串（joinArray 可能为 null，拼接需安全） */
    private static String lowerText(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        for (String key : List.of("categories", "seniority", "title")) {
            String v = "title".equals(key) ? text(node, key) : joinArray(node, key);
            if (v != null) sb.append(v).append(' ');
        }
        return sb.toString().toLowerCase();
    }

    private static String numberText(JsonNode node, String key) {
        JsonNode v = node.get(key);
        if (v == null || v.isNull()) return null;
        String s = v.asText("").trim();
        return s.isBlank() ? null : s;
    }

    private static boolean matchAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }
}
