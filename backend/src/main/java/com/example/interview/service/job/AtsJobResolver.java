package com.example.interview.service.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ATS 岗位链接解析器（v1.58.0，竞品 #14 的**主力路径**）。
 *
 * <p><b>为什么需要它</b>：2026-10-04 三轮实网探测（25 个真实站点）证实，
 * 「服务端抓 HTML 拿 JD」已基本不可行——国内站全部 SPA 或上了 WAF（智联现已返回
 * Tencent Cloud EdgeOne 拦截页），国际站与主流 ATS 的展示页也几乎都是 SPA
 * （实测 {@code jobs.ashbyhq.com/ramp} 只返回 "You need to enable JavaScript to run this app."）。
 *
 * <p><b>但同一批探测也找到了真正的出路</b>：Ashby 与 Greenhouse 为每家公司提供
 * <b>公开、免鉴权、返回完整 JD 正文</b>的发布 API——
 * <ul>
 *   <li>Ashby：{@code GET https://api.ashbyhq.com/posting-api/job-board/<slug>}
 *       → {@code jobs[].descriptionPlain}（**已是纯文本**，实测 4482 字符）</li>
 *   <li>Greenhouse：{@code GET https://boards-api.greenhouse.io/v1/boards/<slug>/jobs?content=true}
 *       → {@code jobs[].content}（HTML 实体转义，需解码，实测 5654 字符）</li>
 * </ul>
 * 本项目既有的 {@code TechBoardJobProvider} **已经在用**这两个 API 做岗位聚合，
 * 所以这里不是引入新依赖，而是**复用同一条已验证的合规数据通道**。
 *
 * <p><b>合规性</b>：只 GET 雇主自己公开发布的招聘页 API；不登录、不投递、不伪造、
 * 不绕过任何鉴权，属公开数据读取，与既有的 13 个数据源同一性质。
 *
 * <p><b>本类不抛异常、不返回 null</b>：识别不了或取不到，一律返回
 * {@link Optional#empty()}，由调用方退化到 HTML 抓取路径。
 */
@Service
public class AtsJobResolver {

    private static final Logger log = LoggerFactory.getLogger(AtsJobResolver.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 单次拉取超时。整板 JSON 可达数 MB，给宽松些；但绝不无限等。 */
    private static final Duration TIMEOUT = Duration.ofSeconds(25);

    /**
     * 拉取上限（字节）。Ashby 的 ramp 整板实测 2.6MB、Greenhouse 的 stripe 实测 5.5MB，
     * 这里给到 16MB 留足余量；超过则视为异常（避免被超大响应打爆内存）。
     */
    private static final int MAX_BYTES = 16 * 1024 * 1024;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            // 不跟随重定向：与 JobPageFetcher 同一条安全理由——
            // 跟随 302 会让攻击者用公网域名把我们导向任意主机。
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** 解析结果：完整 JD 与来源信息，全部取自雇主公开数据，**不含任何模型生成内容**。 */
    public record ResolvedJob(String sourceUrl, String atsName, String title, String company,
                              String location, String descriptionPlain) {
    }

    // ── URL 识别规则 ──

    /** Ashby 托管页：https://jobs.ashbyhq.com/<slug>/<uuid> （也接受无 uuid 的看板页） */
    private static final Pattern ASHBY_PAGE = Pattern.compile(
            "^https?://jobs\\.ashbyhq\\.com/([A-Za-z0-9_.-]+)(?:/([0-9a-fA-F-]{36}))?/?.*$");

    /** Greenhouse 托管页：https://job-boards.greenhouse.io/<slug>/jobs/<id> 或 boards.greenhouse.io/... */
    private static final Pattern GH_PAGE = Pattern.compile(
            "^https?://(?:job-boards|boards)\\.greenhouse\\.io/([A-Za-z0-9_.-]+)/jobs/(\\d+).*$");

    /** 公司官网内嵌 Greenhouse 的常见形态：<任意域名>?gh_jid=<id> —— 这类拿不到 slug，无法调 API，故不处理。 */

    /**
     * 尝试用 ATS 公开 API 解析岗位链接。
     *
     * @param url SSRF 校验并规范化后的 URL（{}**调用方必须先校验**}）
     * @return 命中并成功取到 JD 时返回结果；否则 empty（调用方退化到 HTML 抓取）
     */
    public Optional<ResolvedJob> resolve(String url) {
        if (url == null || url.isBlank()) return Optional.empty();
        try {
            Matcher ashby = ASHBY_PAGE.matcher(url);
            if (ashby.matches()) {
                return resolveAshby(ashby.group(1), ashby.group(2));
            }
            Matcher gh = GH_PAGE.matcher(url);
            if (gh.matches()) {
                return resolveGreenhouse(gh.group(1), gh.group(2));
            }
        } catch (Exception e) {
            // 解析失败绝不影响主流程：退化到 HTML 抓取
            log.warn("ATS 解析异常，退化到 HTML 抓取 url={} err={}", url, e.toString());
        }
        return Optional.empty();
    }

    /**
     * 域名是否属于我们支持的 ATS（供日志与测试判断）。
     *
     * <p><b>为什么用「精确主机名比对」而不是 {@code contains}</b>：{@code contains} 会让
     * {@code jobs.ashbyhq.com.evil.com} 这类**子域混淆**地址通过——它由攻击者控制，
     * 却含有受信域名作为前缀。当前本方法只用于日志，但绝不能让这种模式在代码库里立住：
     * 一旦有人后来拿它做安全判断，漏洞就成立了。**能一眼写对就别留坑。**
     */
    public static boolean isSupportedAts(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            // 精确匹配，或（仅对 greenhouse）允许其官方子域 board 前缀
            return host.equals("jobs.ashbyhq.com")
                    || host.equals("job-boards.greenhouse.io")
                    || host.equals("boards.greenhouse.io");
        } catch (Exception e) {
            return false;
        }
    }

    // ── Ashby ──

    private Optional<ResolvedJob> resolveAshby(String slug, String jobId) {
        if (jobId == null || jobId.isBlank()) {
            // 只有看板页、没有具体岗位 → 没有可提取的单一 JD，交回 HTML 路径（它会判为「非岗位页」）
            log.info("Ashby 链接缺少岗位 id，无法定位单条岗位 slug={}", slug);
            return Optional.empty();
        }
        String api = "https://api.ashbyhq.com/posting-api/job-board/" + slug;
        JsonNode root = fetchJson(api);
        if (root == null) return Optional.empty();

        JsonNode jobs = root.path("jobs");
        if (!jobs.isArray()) return Optional.empty();

        for (JsonNode job : jobs) {
            // Ashby 的 id 就是 URL 里的 UUID
            if (!jobId.equalsIgnoreCase(job.path("id").asText())) continue;

            String desc = job.path("descriptionPlain").asText("");
            if (desc.isBlank()) {
                // descriptionPlain 缺失时退用 HTML 版（调用方会当纯文本处理，故此处不再额外解析）
                desc = stripHtml(job.path("descriptionHtml").asText(""));
            }
            if (desc.isBlank()) {
                log.info("Ashby 岗位无描述字段 slug={} id={}", slug, jobId);
                return Optional.empty();
            }
            String org = root.path("name").asText("");
            if (org.isBlank()) org = prettifySlug(slug);
            return Optional.of(new ResolvedJob(
                    job.path("jobUrl").asText("https://jobs.ashbyhq.com/" + slug + "/" + jobId),
                    "Ashby",
                    job.path("title").asText("").trim(),
                    org.trim(),
                    job.path("location").asText("").trim(),
                    desc.trim()));
        }
        log.info("Ashby 看板中未找到该岗位 slug={} id={}（可能已下架）", slug, jobId);
        return Optional.empty();
    }

    // ── Greenhouse ──

    private Optional<ResolvedJob> resolveGreenhouse(String slug, String jobId) {
        // content=true 才会返回 JD 正文；不带这个参数时 content 字段不存在
        String api = "https://boards-api.greenhouse.io/v1/boards/" + slug + "/jobs/" + jobId + "?content=true";
        JsonNode job = fetchJson(api);

        // 单岗位端点不可用（实测部分 board 返回 404）时，退化为拉整板后本地筛选
        if (job == null || !job.has("title")) {
            job = findGreenhouseJobInBoard(slug, jobId);
            if (job == null) return Optional.empty();
        }

        String content = job.path("content").asText("");
        if (content.isBlank()) {
            log.info("Greenhouse 岗位无 content 字段 slug={} id={}", slug, jobId);
            return Optional.empty();
        }
        // content 是 HTML 实体转义的 HTML：先解实体再剥标签
        String plain = stripHtml(unescapeHtml(content));
        if (plain.isBlank()) return Optional.empty();

        String company = job.path("company_name").asText("");
        if (company.isBlank()) company = prettifySlug(slug);

        return Optional.of(new ResolvedJob(
                job.path("absolute_url").asText("https://job-boards.greenhouse.io/" + slug + "/jobs/" + jobId),
                "Greenhouse",
                job.path("title").asText("").trim(),
                company.trim(),
                job.path("location").path("name").asText(job.path("location").asText("")).trim(),
                plain.trim()));
    }

    private JsonNode findGreenhouseJobInBoard(String slug, String jobId) {
        JsonNode root = fetchJson("https://boards-api.greenhouse.io/v1/boards/" + slug + "/jobs?content=true");
        if (root == null) return null;
        JsonNode jobs = root.path("jobs");
        if (!jobs.isArray()) return null;
        for (JsonNode j : jobs) {
            if (jobId.equals(j.path("id").asText())) return j;
        }
        log.info("Greenhouse 看板中未找到该岗位 slug={} id={}（可能已下架）", slug, jobId);
        return null;
    }

    // ── HTTP 与文本工具 ──

    private JsonNode fetchJson(String apiUrl) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(apiUrl))
                    .header("Accept", "application/json")
                    // 用项目标识而非伪装浏览器：这是对公开 API 的正常调用，不是绕反爬
                    .header("User-Agent", "interview-guide/1.58 (+job-import)")
                    .timeout(TIMEOUT)
                    .GET().build();
            HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                log.info("ATS API 返回非 200：{} {}", resp.statusCode(), apiUrl);
                return null;
            }
            byte[] body = resp.body();
            if (body == null || body.length == 0 || body.length > MAX_BYTES) {
                log.warn("ATS API 响应体积异常：{} bytes url={}", body == null ? -1 : body.length, apiUrl);
                return null;
            }
            return MAPPER.readTree(body);
        } catch (Exception e) {
            log.warn("ATS API 请求失败 url={} err={}", apiUrl, e.toString());
            return null;
        }
    }

    /** 常见 HTML 实体解码。仅为让 JD 可读，不追求完整规范覆盖。 */
    static String unescapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .replace("&nbsp;", " ").replace("&amp;", "&");   // &amp; 必须最后替换
    }

    /**
     * 去掉 HTML 标签，保留文本与换行。
     *
     * <p>复用 {@code JobPageFetcher} 的同一套思路：块级元素前插换行，避免 {@code <div>} 兄弟粘连。
     * 这里不用 Jsoup 而用正则，是因为输入已是**片段**（非完整文档），且必须先解实体再剥标签——
     * 顺序反了会把 {@code &lt;b&gt;} 剥成标签。实测 Ashby/Greenhouse 的 JD 结构简单，正则足够。
     */
    static String stripHtml(String html) {
        if (html == null) return "";
        String s = html;
        // 先整体删除 script/style/noscript 的**标签及其内容**——
        // 只剥标签会留下内容（实测 <style>.a{color:red}</style> 剥后残留 ".a{color:red}"）。
        // (?is) 让 . 匹配换行、忽略大小写；非贪婪避免跨块吞掉后面的正文。
        s = s.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
        s = s.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        s = s.replaceAll("(?is)<noscript[^>]*>.*?</noscript>", " ");
        // 块级边界 → 换行；<br> → 换行
        s = s.replaceAll("(?i)<br\\s*/?>", "\n");
        s = s.replaceAll("(?i)</(p|div|li|tr|h[1-6]|section|article|ul|ol|table)\\s*>", "\n");
        s = s.replaceAll("(?i)<li[^>]*>", "\n• ");
        // 其余标签全部剥掉
        s = s.replaceAll("<[^>]+>", "");
        // 清理
        s = s.replace('\u00A0', ' ')
                .replaceAll("[ \\t]+", " ")
                .replaceAll("(?m)^[ \\t]+", "")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        return s;
    }

    /** slug → 可读公司名（Greenhouse 有时不返回 company_name）：ramp → Ramp */
    static String prettifySlug(String slug) {
        if (slug == null || slug.isBlank()) return "";
        String s = slug.replace('-', ' ').replace('_', ' ').trim();
        if (s.isEmpty()) return slug;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
