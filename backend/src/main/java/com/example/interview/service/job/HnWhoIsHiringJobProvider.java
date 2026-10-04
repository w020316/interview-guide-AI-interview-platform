package com.example.interview.service.job;

import com.example.interview.config.JobAgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Hacker News「Who is hiring?」公开招聘数据源（第四批 · 全时段全行业）。
 *
 * <p>接口：HN 官方 Algolia 搜索 API（{@code https://hn.algolia.com/api/v1}），
 * 公开无需鉴权。每月 1 日前后由账号 whoishiring 发布「Ask HN: Who is hiring?」，
 * 每条顶级评论即一条招聘信息——以初创与远程岗为主，技术/产品/设计/市场等全行业，
 * 且**每月滚动更新**，正好补足平台「全时段」的岗位供给。
 *
 * <p>两步拉取：
 * <ol>
 *   <li>{@code search_by_date?tags=story&author=whoishiring} 取最新一期 hiring 帖
 *       （该账号每月发三个帖：Who is hiring? / Who wants to be hired? / Freelancer?，
 *       按标题含「Who is hiring?」甄别）；</li>
 *   <li>{@code search_by_date?tags=comment,story_<id>&hitsPerPage=N} 取该帖最新的 N 条
 *       评论（时间倒序，保证入库的是最近发布的岗位，而不是帖子里最老的）。</li>
 * </ol>
 *
 * <p><b>数据质量边界</b>：HN 招聘帖为自由文本（首行惯例是「公司 | 职位 | 地点」），
 * 字段靠启发式拆分，解析失败宁缺毋滥（标题/公司取不到的评论直接跳过）。
 */
@Component
public class HnWhoIsHiringJobProvider extends AbstractOpenApiJobProvider {

    private static final String SEARCH_URL =
            "https://hn.algolia.com/api/v1/search_by_date?tags=story&author=whoishiring&hitsPerPage=10";

    /** 首行之外链接的提取（评论区第一个 http 链接通常是申请入口） */
    private static final java.util.regex.Pattern HREF = java.util.regex.Pattern.compile(
            "href=\\\"?(https?://[^\\\"'>\\s]+)", java.util.regex.Pattern.CASE_INSENSITIVE);

    private final JobAgentProperties properties;

    public HnWhoIsHiringJobProvider(ObjectMapper objectMapper, JobAgentProperties properties) {
        super(objectMapper);
        this.properties = properties;
    }

    @Override
    public String platform() {
        return "HN Who is hiring";
    }

    @Override
    public boolean isEnabled() {
        return properties.isOpenApiEnabled();
    }

    /** 基类抽象方法的占位实现：本源覆写了 fetch()（两步拉取），不走基类的单步 JSON 流程 */
    @Override
    protected String endpoint() {
        return SEARCH_URL;
    }

    /** 两步拉取：最新一期帖 → 最新 N 条评论（覆写基类的单步 fetch；两步均为 JSON） */
    @Override
    public List<JobDto> fetch() {
        String threadId = latestHiringThreadId();
        if (threadId == null) {
            throw new IllegalStateException("未找到最新一期 Who is hiring 帖");
        }
        JsonNode comments = readJson(fetchRaw(
                "https://hn.algolia.com/api/v1/search_by_date?tags=comment,story_" + threadId
                        + "&hitsPerPage=" + maxItemsPerRefresh()));
        return applyQuotaForSource(parse(comments));
    }

    /** 最新一期「Who is hiring?」帖（排除 Who wants to be hired / Freelancer） */
    private String latestHiringThreadId() {
        JsonNode search = readJson(fetchRaw(SEARCH_URL));
        for (JsonNode h : search.path("hits")) {
            if (h.path("title").asText("").toLowerCase().contains("who is hiring")) {
                return h.path("objectID").asText(null);
            }
        }
        return null;
    }

    private JsonNode readJson(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException("HN 数据解析失败：" + e.getMessage(), e);
        }
    }

    /** 评论搜索响应 → JobDto 列表；非招聘内容（过短）跳过，宁缺毋滥 */
    @Override
    protected List<JobDto> parse(JsonNode root) {
        List<JobDto> result = new ArrayList<>();
        for (JsonNode c : root.path("hits")) {
            JobDto dto = toDto(c);
            if (dto != null) {
                result.add(dto);
            }
        }
        return result;
    }

    /** 单条评论 → JobDto；非招聘内容（过短）跳过，宁缺毋滥 */
    private JobDto toDto(JsonNode c) {
        String objectId = c.path("objectID").asText(null);
        String html = c.path("text").asText(null);
        if (objectId == null || html == null || html.isBlank()) {
            return null;
        }
        String plain = plainText(html, DESC_MAX_LEN);
        if (plain == null || plain.length() < 40) {
            return null;    // 过短的评论不是招聘帖（占位/讨论）
        }
        // 首行惯例是「公司 | 职位 | 地点」：公司取第一段，职位取整行
        String firstLine = plain.split("\\R", 2)[0].trim();
        String title = firstLine.length() > 120 ? firstLine.substring(0, 120) : firstLine;
        String company = companyNameOf(firstLine, c.path("author").asText(""));
        LocalDate posted = parseDatePrefix(c.path("created_at").asText(null));
        String url = firstExternalUrl(html);
        String location = plain.toLowerCase().contains("remote") ? "全球远程" : null;

        return new JobDto(
                clip("hn-" + objectId, LEN_EXTERNAL_ID),
                clip(title, LEN_TITLE),
                clip(company, LEN_COMPANY),
                "互联网",
                jobTypeOf(plain),
                clip(location, LEN_LOCATION),
                null,
                "不限",
                "不限",
                "SOCIAL",
                deadlineFrom(posted, 60),
                clip(url != null ? url : "https://news.ycombinator.com/item?id=" + objectId, LEN_URL),
                clip(plain, DESC_MAX_LEN),
                null,
                null
        );
    }

    /** 公司：首行按常见分隔符切第一段；取不到时用发帖作者（招聘方） */
    private static String companyNameOf(String firstLine, String author) {
        for (String sep : new String[]{" | ", "｜", " · ", " — "}) {
            int idx = firstLine.indexOf(sep);
            if (idx > 0) {
                return firstLine.substring(0, idx).trim();
            }
        }
        return author;
    }

    /** 评论区第一个外部链接（申请入口）；HN 站内链接不算 */
    private static String firstExternalUrl(String html) {
        java.util.regex.Matcher m = HREF.matcher(html);
        while (m.find()) {
            String u = m.group(1);
            if (!u.contains("news.ycombinator.com")) {
                return u;
            }
        }
        return null;
    }

    /** 职位类型：依据文本关键词粗分类（与 RemoteOK 同源启发式，HN 帖亦含非技术岗） */
    private static String jobTypeOf(String t) {
        String s = t.toLowerCase();
        if (matchAny(s, "market", "growth", "seo", "content", "brand")) return "市场";
        if (matchAny(s, "sales", "account exec", "business development")) return "销售";
        if (matchAny(s, "design", "ux", "ui ", "graphic")) return "设计";
        if (matchAny(s, "product manager", "product owner", "product lead")) return "产品";
        if (matchAny(s, "recruit", "talent", "human resources", "people ops")) return "职能";
        if (matchAny(s, "support", "customer success", "customer service")) return "客服";
        if (matchAny(s, "finance", "accounting", "controller")) return "金融";
        if (matchAny(s, "data engineer", "data scientist", "data analyst", "machine learning")) return "数据";
        return "技术";
    }

    private static boolean matchAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }
}
