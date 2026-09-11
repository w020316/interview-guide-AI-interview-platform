package com.example.interview.service.job;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 智能体联网招聘搜索服务（v1.31.0）
 *
 * 职责：应智能体 searchWebJobs 工具之需，通过 jsoup 实时抓取公开招聘站点搜索页，
 * 返回全国各地区的真实岗位信息，不受本地种子/适配器入库数据限制。
 *
 * 设计约束：
 * 1. 只读且平稳：全部为网络读取，无写库、无副作用；抓取失败不抛异常，
 *    返回空列表交由调用方降级（回退本地库），从而保证智能体"回复正常"。
 * 2. 真实来源：从公开招聘搜索页提取岗位（智联/BOSS直聘/拉勾/51job 等），
 *    每小时采样候选源，解析失败即跳过该源，多源并行提升命中率。
 * 3. 网络稳健：连接/读取超时、UA 伪装、URL 编码，防止反爬与挂死。
 * 4. 结果裁剪：单源最多取 N 条，字段标准化，避免撑爆智能体上下文。
 *
 * 重要说明：公开招聘站点多为 JS 动态渲染，jsoup 仅能解析服务端渲染/静态的搜索页。
 * 当目标站点关闭静态入口时会自动降级到下一源；若全部不可用则返回空，由工具层回退
 * 本地聚合数据——这是保证智能体稳定回复的关键兜底。
 */
@Service
public class WebJobSearcherService {

    private static final Logger log = LoggerFactory.getLogger(WebJobSearcherService.class);

    /** 单源最多返回条数（控制上下文体积） */
    private static final int MAX_PER_SOURCE = 8;
    /** 最大结果总数 */
    private static final int MAX_TOTAL = 12;
    /** 连接/读取超时（毫秒） */
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 8000;

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    /** 标准化岗位记录 */
    public record WebJob(String title, String company, String location,
                         String salary, String degree, String deadline, String applyUrl) {
    }

    /**
     * 联网搜索岗位。location 可为空（表示全国/不限定），keyword 为岗位关键词。
     * 返回真实抓取结果；无任何源可用时返回空列表（调用方据此降级）。
     */
    public List<WebJob> searchWeb(String keyword, String location) {
        String kw = (keyword == null || keyword.isBlank()) ? "java" : keyword.trim();
        String loc = (location == null || location.isBlank()) ? "全国" : location.trim();

        List<WebJob> collected = new ArrayList<>();
        // 候选源按稳健度排序：先试易静态解析的搜索入口
        List<Runnable> sources = List.of(
                () -> fetchFromZhilian(kw, loc, collected),
                () -> fetchFromLagou(kw, loc, collected),
                () -> fetchFromJob51(kw, loc, collected),
                () -> fetchFromZhipin(kw, loc, collected));

        for (Runnable source : sources) {
            try {
                source.run();
            } catch (Exception e) {
                log.warn("联网招聘源抓取异常：{}", e.getMessage());
            }
            if (collected.size() >= MAX_TOTAL) {
                break;
            }
        }
        if (collected.isEmpty()) {
            log.info("联网招聘搜索无可用静态源，keyword={}, location={}，交由调用方降级", kw, loc);
        }
        return collected.size() > MAX_TOTAL ? collected.subList(0, MAX_TOTAL) : collected;
    }

    // ---------- 各候选源抓取实现 ----------

    /** 智联招聘搜索页：https://sou.zhaopin.com/?kw=&jl=（SSR 内嵌 JSON 岗位数据，真实可抓） */
    private void fetchFromZhilian(String kw, String loc, List<WebJob> out) {
        String url = "https://sou.zhaopin.com/?kw=" + enc(kw)
                + (isNational(loc) ? "" : "&jl=" + enc(loc));
        Document doc = fetch(url);
        if (doc == null) return;
        List<WebJob> parsed = parseZhilianHtml(doc.html(), loc);
        for (WebJob w : parsed) {
            if (out.size() >= MAX_PER_SOURCE) break;
            out.add(w);
        }
    }

    /** 从智联 SSR HTML 提取岗位（包可见，便于测试；不依赖网络） */
    List<WebJob> parseZhilianHtml(String html, String loc) {
        List<WebJob> result = new ArrayList<>();
        if (html == null || html.isBlank()) return result;
        // 智联 SSR 把岗位数据以 JSON 形式注入脚本，每条岗位在一个 "position":{"base":{...}} 对象内，
        // positionName 与其相邻的 salary/workingExp 同在 base 块中。用 positionName + 有界窗口提取，避免跨岗位错配。
        java.util.regex.Pattern pat = java.util.regex.Pattern.compile(
                "\"positionName\"\\s*:\\s*\"([^\"]{1,80})\"", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher m = pat.matcher(html);
        while (m.find() && result.size() < MAX_PER_SOURCE) {
            String title = unescapeJson(m.group(1));
            if (title.isBlank()) continue;
            // salary/workingExp/education 在 positionName 之后 800 字符内（同一 base 块）
            int end = Math.min(html.length(), m.start() + 800);
            String seg = html.substring(m.start(), end);
            String salary = fieldAfter(seg, "salary");
            String workingExp = fieldAfter(seg, "positionWorkingExp");
            String education = fieldAfter(seg, "education");
            // 企业名/城市在整页 VM 数据里，就近向前取（粗粒度但稳定）
            String company = nearField(html, m.start(), "companyName");
            String city = nearField(html, m.start(), "cityName");
            result.add(new WebJob(title, company.isBlank() ? "智联招聘" : company,
                    city.isBlank() ? (isNational(loc) ? "全国" : loc) : city,
                    salary.isBlank() ? "面议" : salary,
                    education.isBlank() ? "" : education,
                    workingExp.isBlank() ? "" : workingExp, "https://sou.zhaopin.com"));
        }
        return result;
    }

    /** 在 segment 内查找某 JSON 字段的字符串值 */
    private static String fieldAfter(String seg, String field) {
        int i = seg.indexOf("\"" + field + "\"");
        if (i < 0) return "";
        String sub = seg.substring(i, Math.min(seg.length(), i + 90));
        java.util.regex.Matcher v = java.util.regex.Pattern.compile(":\\s*\"([^\"]{1,60})\"").matcher(sub);
        return v.find() ? v.group(1).replace("\\u003d", "=").trim() : "";
    }

    /** 在 html 的 anchor 位置向前就近找某个 JSON 字段的值（粗粒度但稳定） */
    private static String nearField(String html, int anchor, String field) {
        int idx = html.lastIndexOf("\"" + field + "\"", anchor);
        if (idx < 0 || anchor - idx > 5000) return "";
        String seg = html.substring(idx, Math.min(html.length(), idx + 120));
        java.util.regex.Matcher f = java.util.regex.Pattern
                .compile(":(\"[^\"]{1,60}|[0-9A-Za-z_-]{1,40})").matcher(seg);
        return f.find() ? unescapeJson(f.group(1).replace("\"", "")).trim() : "";
    }

    private static boolean isNational(String loc) {
        return loc == null || loc.isBlank() || "全国".equals(loc) || "不限".equals(loc);
    }

    private static String unescapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\u003d", "=").replace("\"", "").trim();
    }

    /** 拉勾网搜索：https://www.lagou.com/jobs/list_java?city=全国 */
    private void fetchFromLagou(String kw, String loc, List<WebJob> out) {
        String url = "https://www.lagou.com/jobs/list_" + enc(kw) + "?city=" + enc(loc);
        Document doc = fetch(url);
        if (doc == null) return;
        Elements cards = doc.select("li.con_list_item, li.job_list_li");
        if (cards.isEmpty()) return;
        for (Element c : cards) {
            if (out.size() >= MAX_PER_SOURCE) break;
            String title = text(c.select(".position_name, .position"));
            String company = text(c.select(".company_name, .company"));
            if (title.isBlank() && company.isBlank()) continue;
            String salary = text(c.select(".salary, .money"));
            String need = text(c.select(".position_labels, .labels"));
            out.add(new WebJob(title.isBlank() ? kw : title, company, loc,
                    salary, need, "", absHref(c.select("a"))));
        }
    }

    /** 前程无忧搜索：https://we.51job.com/pc/search?keyword=&jobArea= */
    private void fetchFromJob51(String kw, String loc, List<WebJob> out) {
        String url = "https://we.51job.com/pc/search?keyword=" + enc(kw) + "&jobArea=" + enc(loc);
        Document doc = fetch(url);
        if (doc == null) return;
        Elements cards = doc.select("div.joblist, div.job_title, div.e");
        if (cards.isEmpty()) return;
        for (Element c : cards) {
            if (out.size() >= MAX_PER_SOURCE) break;
            String title = text(c.select(".jname, .t1, .job_title"));
            String company = text(c.select(".company_name, .cname"));
            if (title.isBlank()) continue;
            out.add(new WebJob(title, company, loc,
                    text(c.select(".sal, .sl")), "", "", absHref(c.select("a"))));
        }
    }

    /** BOSS直聘搜索：https://www.zhipin.com/web/geek/job?query=&city= */
    private void fetchFromZhipin(String kw, String loc, List<WebJob> out) {
        String city = mapZhipinCity(loc);
        String url = "https://www.zhipin.com/web/geek/job?query=" + enc(kw)
                + (city.isBlank() ? "" : "&city=" + city);
        // BOSS 为强 JS 渲染站点，jsoup 通常抓不到岗位列表，此处仅尝试，失败即空
        Document doc = fetch(url);
        if (doc == null) return;
        Elements cards = doc.select(".job-card-wrapper, li.job-card-wrapper, .job-primary");
        if (cards.isEmpty()) return;
        for (Element c : cards) {
            if (out.size() >= MAX_PER_SOURCE) break;
            String title = text(c.select(".job-name, .job-title"));
            String company = text(c.select(".company-name, .company-text"));
            if (title.isBlank()) continue;
            out.add(new WebJob(title, company, loc,
                    text(c.select(".salary")), "", "", absHref(c.select("a"))));
        }
    }

    // ---------- 辅助 ----------

    private Document fetch(String url) {
        try {
            return Jsoup.connect(url)
                    .userAgent(UA)
                    .timeout(READ_TIMEOUT_MS)
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .followRedirects(true)
                    .ignoreHttpErrors(true)
                    .get();
        } catch (SocketTimeoutException e) {
            log.warn("联网抓取超时：{}", url);
            return null;
        } catch (IOException | IllegalArgumentException e) {
            log.warn("联网抓取失败：{} {}", url, e.getMessage());
            return null;
        }
    }

    private static String text(Elements els) {
        if (els == null || els.isEmpty()) return "";
        String s = els.first().text().trim();
        return s;
    }

    private static String absHref(Elements aEls) {
        for (Element a : aEls) {
            String href = a.absUrl("href");
            if (!href.isBlank()) return href;
        }
        return "";
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8.toString());
        } catch (Exception e) {
            return s;
        }
    }

    /** 全国通用城市映射（BOSS 用城市码，展示用中文） */
    private static String mapZhipinCity(String loc) {
        if (loc == null || loc.isBlank() || "全国".equals(loc)) return "";
        java.util.Map<String, String> m = new java.util.HashMap<>();
        m.put("北京", "101010100"); m.put("上海", "101020100"); m.put("广州", "101280100");
        m.put("深圳", "101280600"); m.put("杭州", "101210100"); m.put("成都", "101270100");
        m.put("武汉", "101200100"); m.put("南京", "101190100"); m.put("西安", "101110100");
        m.put("苏州", "101190400"); m.put("天津", "101030100"); m.put("重庆", "101040100");
        m.put("长沙", "101250100"); m.put("郑州", "101180100"); m.put("东莞", "101281600");
        return m.getOrDefault(loc, "");
    }
}