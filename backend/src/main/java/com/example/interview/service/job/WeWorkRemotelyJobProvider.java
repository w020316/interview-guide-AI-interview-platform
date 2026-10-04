package com.example.interview.service.job;

import com.example.interview.config.JobAgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * We Work Remotely 公开招聘数据源（第四批 · 全时段全行业）。
 *
 * <p>接口：官方 RSS（{@code https://weworkremotely.com/remote-jobs.rss}），公开无需鉴权。
 * WWR 是最大的远程招聘板之一，覆盖**技术/设计/产品/市场/销售/客服**等全行业远程岗，
 * 与「全时段全行业」的平台定位直接对应；每条目自带公司、职位、地区与详情。
 *
 * <p>解析用 JDK 内置 DOM（零新增依赖），结构稳定；description 为 CDATA 的 HTML 片段，
 * 复用基类的 {@code plainText} 清洗为纯文本。
 */
@Component
public class WeWorkRemotelyJobProvider extends AbstractOpenApiJobProvider {

    private static final String FEED_URL = "https://weworkremotely.com/remote-jobs.rss";

    private final JobAgentProperties properties;

    public WeWorkRemotelyJobProvider(ObjectMapper objectMapper, JobAgentProperties properties) {
        super(objectMapper);
        this.properties = properties;
    }

    @Override
    public String platform() {
        return "WeWorkRemotely";
    }

    @Override
    public boolean isEnabled() {
        return properties.isOpenApiEnabled();
    }

    /** 基类抽象方法的占位实现：本源覆写了 fetch()（RSS/XML），不走基类的单步 JSON 流程 */
    @Override
    protected String endpoint() {
        return FEED_URL;
    }

    /** RSS 为 XML：覆写 fetch，直接解析（不走基类的 JSON readTree） */
    @Override
    public List<JobDto> fetch() {
        String xml = fetchRaw(FEED_URL);
        List<JobDto> parsed = parseRss(xml);
        return applyQuotaForSource(parsed);
    }

    /** RSS 解析（包级可见，便于单测）：title 惯例为「Company: Job Title (Region)」 */
    List<JobDto> parseRss(String xml) {
        List<JobDto> result = new ArrayList<>();
        try {
            var factory = DocumentBuilderFactory.newInstance();
            // 禁用外部实体（XXE 防护）：上游是公开 RSS，防御性关闭 DTD 与外部实体解析
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            var doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            var items = doc.getElementsByTagName("item");
            for (int i = 0; i < items.getLength(); i++) {
                Element it = (Element) items.item(i);
                String rawTitle = textOf(it, "title");
                String link = textOf(it, "link");
                if (rawTitle == null || link == null) {
                    continue;
                }
                // 惯例格式「Company: Job Title (Region)」；拆不开时整行作为职位
                String titleLine = rawTitle.trim();
                String company = null;
                String region = null;
                int colon = titleLine.indexOf(": ");
                if (colon > 0) {
                    company = titleLine.substring(0, colon).trim();
                    titleLine = titleLine.substring(colon + 2).trim();
                }
                int open = titleLine.lastIndexOf('(');
                int close = titleLine.lastIndexOf(')');
                if (open > 0 && close > open) {
                    region = titleLine.substring(open + 1, close).trim();
                    titleLine = titleLine.substring(0, open).trim();
                }
                String category = textOf(it, "category");

                result.add(new JobDto(
                        clip("wwr-" + slugOf(link), LEN_EXTERNAL_ID),
                        clip(titleLine, LEN_TITLE),
                        clip(company, LEN_COMPANY),
                        "不限",
                        jobTypeOf(category),
                        clip(locationOf(region), LEN_LOCATION),
                        null,
                        "不限",
                        "不限",
                        "SOCIAL",
                        null,   // 上游无截止信息 → 留空
                        clip(link, LEN_URL),
                        plainText(textOf(it, "description"), DESC_MAX_LEN),
                        null,
                        clip(category, LEN_TAGS)
                ));
            }
        } catch (Exception e) {
            throw new IllegalStateException("WWR RSS 解析失败：" + e.getMessage(), e);
        }
        return result;
    }

    private static String textOf(Element parent, String tag) {
        var nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) return null;
        String s = nodes.item(0).getTextContent();
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String slugOf(String link) {
        String s = link.trim();
        int q = s.indexOf('?');
        if (q > 0) s = s.substring(0, q);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        int slash = s.lastIndexOf('/');
        return slash >= 0 ? s.substring(slash + 1) : s;
    }

    /** 地点：WWR 全站即远程岗；无地区标注时统一「全球远程」 */
    private static String locationOf(String region) {
        if (region == null || region.isBlank()) return "全球远程";
        return region;
    }

    /** 职位类型：按 WWR 分类映射（该源天然跨行业） */
    private static String jobTypeOf(String category) {
        String c = (category == null ? "" : category).toLowerCase(Locale.ROOT);
        if (c.contains("programming") || c.contains("dev")) return "技术";
        if (c.contains("design")) return "设计";
        if (c.contains("product")) return "产品";
        if (c.contains("market") || c.contains("growth")) return "市场";
        if (c.contains("customer support") || c.contains("support")) return "客服";
        if (c.contains("sales") || c.contains("business")) return "销售";
        if (c.contains("finance") || c.contains("legal")) return "金融";
        if (c.contains("hr") || c.contains("recruit")) return "职能";
        return "不限";
    }
}
