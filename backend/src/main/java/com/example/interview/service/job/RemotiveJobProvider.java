package com.example.interview.service.job;

import com.example.interview.config.JobAgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Remotive 公开招聘数据源（v1.37.0）
 *
 * <p>接口：{@code https://remotive.com/api/remote-jobs?limit=100}，公开无需鉴权，
 * 岗位位于 {@code jobs} 数组，每条按 {@code category} 做了结构化分类——
 * 正好补上 RemoteOK 只有自由标签、无分类的短板，两者的行业/职位类型分布互补。
 */
@Component
public class RemotiveJobProvider extends AbstractOpenApiJobProvider {

    private final JobAgentProperties properties;

    public RemotiveJobProvider(ObjectMapper objectMapper, JobAgentProperties properties) {
        super(objectMapper);
        this.properties = properties;
    }

    @Override
    public String platform() {
        return "Remotive 全球远程";
    }

    @Override
    public boolean isEnabled() {
        return properties.isOpenApiEnabled();
    }

    @Override
    protected String endpoint() {
        return "https://remotive.com/api/remote-jobs?limit=100";
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
            String company = text(node, "company_name", "company");
            String rawId = text(node, "id");
            if (title == null || company == null || rawId == null) {
                continue;
            }
            LocalDate posted = parseDatePrefix(text(node, "publication_date"));

            result.add(new JobDto(
                    clip("rmt-" + rawId, LEN_EXTERNAL_ID),
                    clip(title, LEN_TITLE),
                    clip(company, LEN_COMPANY),
                    industryOf(node),
                    jobTypeOf(node),
                    clip(locationOf(node), LEN_LOCATION),
                    clip(text(node, "salary"), LEN_SALARY),
                    "不限",
                    "不限",
                    "SOCIAL",
                    deadlineFrom(posted, 60),
                    clip(text(node, "url"), LEN_URL),
                    desc(node),
                    null,
                    clip(tagsOf(node), LEN_TAGS)
            ));
        }
        return result;
    }

    /** 行业：由 category 映射到平台既有的中文行业枚举，避免筛选列表被英文分类污染 */
    private static String industryOf(JsonNode node) {
        String cat = text(node, "category");
        if (cat == null) return "互联网";
        String c = cat.toLowerCase();
        if (c.contains("finance") || c.contains("legal")) return "金融";
        if (c.contains("health") || c.contains("medical")) return "医疗";
        if (c.contains("education")) return "教育";
        if (c.contains("market") || c.contains("content") || c.contains("write")) return "传媒";
        if (c.contains("human resources")) return "人力资源";
        if (c.contains("customer") || c.contains("support")) return "客服";
        if (c.contains("design") || c.contains("art")) return "设计";
        if (c.contains("sales") || c.contains("business")) return "销售";
        return "互联网";
    }

    /** 地点：candidate_required_location 为 Worldwide / Anywhere 时归一为「全球远程」 */
    private static String locationOf(JsonNode node) {
        String loc = text(node, "candidate_required_location");
        if (loc == null) return "全球远程";
        String l = loc.toLowerCase();
        if (l.contains("worldwide") || l.contains("anywhere")) return "全球远程";
        return loc;
    }

    private static String jobTypeOf(JsonNode node) {
        String cat = text(node, "category");
        String title = text(node, "title");
        String t = ((cat == null ? "" : cat) + " " + (title == null ? "" : title)).toLowerCase();
        if (matchAny(t, "marketing", "growth", "seo", "content")) return "市场";
        if (matchAny(t, "sales", "business")) return "销售";
        if (matchAny(t, "design", "ux", "ui", "graphic")) return "设计";
        if (matchAny(t, "product")) return "产品";
        if (matchAny(t, "human resources", "recruit", "talent")) return "职能";
        if (matchAny(t, "customer", "support")) return "客服";
        if (matchAny(t, "finance", "legal", "accounting")) return "金融";
        if (matchAny(t, "project management", "operations")) return "职能";
        return "技术";
    }

    /** 标签：tags + job_type（full_time/contract 等）合并，便于前端展示与筛选 */
    private static String tagsOf(JsonNode node) {
        String tags = joinArray(node, "tags");
        String jobType = text(node, "job_type");
        if (tags == null) return jobType;
        if (jobType == null) return tags;
        return tags + "," + jobType;
    }

    private static boolean matchAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }
}
