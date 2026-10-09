package com.example.interview.service.job;

import com.example.interview.util.SsrUrlValidator;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 智能体联网招聘搜索服务（v1.31.0；v1.59.0 修正「静默空转」）
 *
 * <p><b>⚠️ 2026-10-05 实测结论（本条是本节最重要的事实，改动前必读）</b>：
 * 本类原先依赖的 4 个国内招聘搜索入口（智联 / 拉勾 / 前程无忧 / BOSS直聘）
 * <b>已全部结构性失效</b>，且不是暂时性故障：
 * <ul>
 *   <li><b>智联</b>：已接入腾讯云 EdgeOne，所有请求返回约 2.2KB 的
 *       {@code "Protected by Tencent Cloud EdgeOne"} 拦截页（**搜索页与详情页皆然**）。
 *       原注释「SSR 内嵌 JSON 岗位数据，真实可抓」写时是真的，**现已相反**。</li>
 *   <li><b>拉勾 / 前程无忧 / BOSS直聘</b>：返回空壳页面（正文 100~400 字节），
 *       岗位内容由前端 JS 异步加载，服务端读不到。</li>
 * </ul>
 * 实测 {@code searchWeb} 三次查询（java/北京、产品经理/全国、算法/深圳）
 * <b>产出恒为 0 条</b>，却仍耗费 287~1224ms —— <b>一个必然空转的函数</b>。
 *
 * <p><b>因此 v1.59.0 的行为约定</b>：
 * <ol>
 *   <li><b>不再静默空转</b>：这些入口把结果写入 {@link JobSourceHealthRegistry}，
 *       管理后台「数据源」视图能直接看到它们连续失败 —— 失效<b>可见</b>。</li>
 *   <li><b>不删除能力</b>：若上游将来放开（如智联撤掉 WAF），把对应解析逻辑恢复即可；
 *       保留解析器与单测，避免下次要重写。</li>
 *   <li><b>调用方话术必须同步改</b>：{@code AgentTools.searchWebJobs} 原先说
 *       「暂未抓取到新岗位」——把结构性失效说成了暂时性问题，会误导用户以为
 *       「多试几次就好」或「是自己网络的问题」。见该方法的注释。</li>
 * </ol>
 *
 * <p><b>真正可用的中文岗位来源是本地聚合库</b>（{@code JobAgentService}）——
 * 它由 13 个真实可用的数据源刷新：海外 5 源（RemoteOK/Remotive/Arbeitnow/Himalayas/Jobicy）
 * + V2EX 酷工作 + Ashby / Greenhouse 官方招聘板。**联网抓国内站不可行时不假装可行。**
 *
 * <p>原有设计约束（仍适用）：
 * <ol>
 *   <li>只读且平稳：全部为网络读取，无写库、无副作用；抓取失败不抛异常，
 *       返回空列表交由调用方降级（回退本地库），从而保证智能体"回复正常"。</li>
 *   <li>结果裁剪：单源最多取 N 条，字段标准化，避免撑爆智能体上下文。</li>
 * </ol>
 */
@Service
public class WebJobSearcherService {

    private static final Logger log = LoggerFactory.getLogger(WebJobSearcherService.class);

    /** 单源最多返回条数（控制上下文体积） */
    private static final int MAX_PER_SOURCE = 8;
    /** 最大结果总数 */
    private static final int MAX_TOTAL = 12;
    /** 读取超时（毫秒）；Jsoup.connect().timeout() 同时作用于连接与读取，连接超时无需单独常量 */
    private static final int READ_TIMEOUT_MS = 8000;

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0 Safari/537.36";

    /**
     * 数据源健康登记表（让「联网源全部失效」这件事在后台可见）。
     *
     * <p><b>为什么用可空 setter 注入而不是构造器注入</b>：本类被
     * {@code WebJobSearcherServiceTest} 直接用 {@code new} 构造并单测解析逻辑，
     * 构造器注入会迫使全部单测改造成 Spring 上下文。而登记表只是「可选的观测出口」，
     * 缺失时跳过登记即可，不影响搜索本身的正确性。
     */
    private JobSourceHealthRegistry healthRegistry;

    @Autowired(required = false)
    public void setHealthRegistry(JobSourceHealthRegistry healthRegistry) {
        this.healthRegistry = healthRegistry;
    }

    /** 标准化岗位记录 */
    public record WebJob(String title, String company, String location,
                         String salary, String degree, String deadline, String applyUrl) {
    }

    /**
     * 联网搜索岗位。location 可为空（表示全国/不限定），keyword 为岗位关键词。
     * 返回真实抓取结果；无任何源可用时返回空列表（调用方据此降级）。
     *
     * <p><b>v1.59.0：每个源的成败都写入 {@link JobSourceHealthRegistry}</b>。
     * 这样管理后台能直接看到「智联连续失败 N 次」——而不是让运营方
     * 只观察到「岗位总数变少了」却不知原因。
     */
    public List<WebJob> searchWeb(String keyword, String location) {
        String kw = (keyword == null || keyword.isBlank()) ? "java" : keyword.trim();
        String loc = (location == null || location.isBlank()) ? "全国" : location.trim();

        List<WebJob> collected = new ArrayList<>();
        // 候选源按稳健度排序：先试易静态解析的搜索入口
        List<Source> sources = List.of(
                new Source(SOURCE_ZHILIAN, () -> fetchFromZhilian(kw, loc, collected)),
                new Source(SOURCE_LAGOU, () -> fetchFromLagou(kw, loc, collected)),
                new Source(SOURCE_51JOB, () -> fetchFromJob51(kw, loc, collected)),
                new Source(SOURCE_ZHIPIN, () -> fetchFromZhipin(kw, loc, collected)));

        for (Source source : sources) {
            int before = collected.size();
            long start = System.currentTimeMillis();
            try {
                source.action().run();
            } catch (Exception e) {
                log.warn("联网招聘源抓取异常：{}", e.getMessage());
            }
            long elapsed = System.currentTimeMillis() - start;
            int gained = collected.size() - before;
            // 把每个源的结果登记下来：这是「失效可见」的落点
            registerHealth(source.name(), gained, elapsed);
            if (collected.size() >= MAX_TOTAL) {
                break;
            }
        }
        if (collected.isEmpty()) {
            log.info("联网招聘搜索无可用静态源，keyword={}, location={}，交由调用方降级", kw, loc);
        }
        return collected.size() > MAX_TOTAL ? collected.subList(0, MAX_TOTAL) : collected;
    }

    /**
     * 登记一个源本轮的结果。{@code healthRegistry} 为 null（单测直接 new、未注入）时静默跳过。
     *
     * <p>抽成独立方法是为了让 {@code searchWeb} 的循环体保持扁平，也便于单测
     * 用一个假登记表验证「成功/失败各自登记了什么」。
     */
    private void registerHealth(String name, int gained, long elapsedMs) {
        if (healthRegistry == null) {
            return;
        }
        if (gained > 0) {
            healthRegistry.recordSuccess(name, gained, elapsedMs);
        } else {
            healthRegistry.recordFailure(name,
                    "未从该站点搜索页提取到任何岗位（页面可能为 JS 动态渲染或已被反爬拦截）", elapsedMs);
        }
    }

    /** 一个候选源的展示名与抓取动作。 */
    private record Source(String name, Runnable action) {
    }

    /** 数据源展示名：与 {@link JobSourceHealthRegistry} 中的键一致，便于后台聚合展示 */
    static final String SOURCE_ZHILIAN = "智联招聘(联网)";
    static final String SOURCE_LAGOU = "拉勾网(联网)";
    static final String SOURCE_51JOB = "前程无忧(联网)";
    static final String SOURCE_ZHIPIN = "BOSS直聘(联网)";

    // ---------- 各候选源抓取实现 ----------

    /**
     * 把某个源的解析结果并入总列表，单源最多并入 {@code limit} 条。
     *
     * <p>v1.34.1 修复（P1）：必须按「本源已并入条数」计数，**不得**用共享总列表的
     * {@code out.size()} 判断——否则首个源填满上限后，后续源首次判断即中断，
     * 恒贡献 0 条，多源聚合名存实亡（且 MAX_TOTAL 永远达不到）。
     *
     * @return 本次实际并入的条数
     */
    static int mergeFromSource(List<WebJob> out, List<WebJob> fromSource, int limit) {
        if (fromSource == null || fromSource.isEmpty()) {
            return 0;
        }
        int added = 0;
        for (WebJob w : fromSource) {
            if (added >= limit) break;
            out.add(w);
            added++;
        }
        return added;
    }

    /** 智联招聘搜索页：https://sou.zhaopin.com/?kw=&jl=（**⚠️ 2026-10 起被腾讯云 EdgeOne 拦截，见类注释**） */
    private void fetchFromZhilian(String kw, String loc, List<WebJob> out) {
        String url = "https://sou.zhaopin.com/?kw=" + enc(kw)
                + (isNational(loc) ? "" : "&jl=" + enc(loc));
        Document doc = fetch(url);
        if (doc == null) return;
        mergeFromSource(out, parseZhilianHtml(doc.html(), loc), MAX_PER_SOURCE);
    }

    /** 从智联 SSR HTML 提取岗位（包可见，便于测试；不依赖网络）。
     *
     * <p>⚠️ 解析逻辑本身仍然正确，保留它是为了「上游若撤掉 WAF 可立即恢复」——
     * 但**当前线上拿不到含有这些字段的 HTML**，所以调用它的网络路径恒为空。
     * 单测仍覆盖本方法，保证解析器不腐化。 */
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

    /** 拉勾网搜索：https://www.lagou.com/jobs/list_java?city=全国（**⚠️ JS 渲染空壳，见类注释**） */
    private void fetchFromLagou(String kw, String loc, List<WebJob> out) {
        String url = "https://www.lagou.com/jobs/list_" + enc(kw) + "?city=" + enc(loc);
        Document doc = fetch(url);
        if (doc == null) return;
        Elements cards = doc.select("li.con_list_item, li.job_list_li");
        if (cards.isEmpty()) return;
        List<WebJob> parsed = new ArrayList<>();
        for (Element c : cards) {
            String title = text(c.select(".position_name, .position"));
            String company = text(c.select(".company_name, .company"));
            if (title.isBlank() && company.isBlank()) continue;
            parsed.add(new WebJob(title.isBlank() ? kw : title, company, loc,
                    text(c.select(".salary, .money")), text(c.select(".position_labels, .labels")),
                    "", absHref(c.select("a"))));
        }
        mergeFromSource(out, parsed, MAX_PER_SOURCE);
    }

    /** 前程无忧搜索：https://we.51job.com/pc/search?keyword=&jobArea=（**⚠️ JS 渲染空壳，见类注释**） */
    private void fetchFromJob51(String kw, String loc, List<WebJob> out) {
        String url = "https://we.51job.com/pc/search?keyword=" + enc(kw) + "&jobArea=" + enc(loc);
        Document doc = fetch(url);
        if (doc == null) return;
        Elements cards = doc.select("div.joblist, div.job_title, div.e");
        if (cards.isEmpty()) return;
        List<WebJob> parsed = new ArrayList<>();
        for (Element c : cards) {
            String title = text(c.select(".jname, .t1, .job_title"));
            if (title.isBlank()) continue;
            parsed.add(new WebJob(title, text(c.select(".company_name, .cname")), loc,
                    text(c.select(".sal, .sl")), "", "", absHref(c.select("a"))));
        }
        mergeFromSource(out, parsed, MAX_PER_SOURCE);
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
        List<WebJob> parsed = new ArrayList<>();
        for (Element c : cards) {
            String title = text(c.select(".job-name, .job-title"));
            if (title.isBlank()) continue;
            parsed.add(new WebJob(title, text(c.select(".company-name, .company-text")), loc,
                    text(c.select(".salary")), "", "", absHref(c.select("a"))));
        }
        mergeFromSource(out, parsed, MAX_PER_SOURCE);
    }

    // ---------- 辅助 ----------

    private Document fetch(String url) {
        // B-07：与 JobPageFetcher 对齐——先过 SSRF 校验（当前 URL 全为硬编码公开站点，
        // 此校验为纵深防御：若将来支持用户自定义源，可直接拦住内网/元数据地址），
        // 且 followRedirects(false) 防止「跟随 302 跳转到内网」绕过校验。
        if (!SsrUrlValidator.validate(url).ok) {
            log.warn("拒绝抓取未通过 SSRF 校验的地址：{}", url);
            return null;
        }
        try {
            return Jsoup.connect(url)
                    .userAgent(UA)
                    .timeout(READ_TIMEOUT_MS)
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .followRedirects(false)
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