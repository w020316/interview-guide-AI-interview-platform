package com.example.interview.service.job;

import com.example.interview.util.SsrUrlValidator;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

/**
 * 岗位链接抓取器：把用户粘贴的招聘页面 URL 安全地抓下来，提取出可送 AI 的正文。
 *
 * <p><b>为什么要单开一个类、而不是照着 {@code ResumeController.importFromUrl} 再抄一遍</b>：
 * 抓取用户传入 URL 是本项目里风险最高的一类操作（SSRF），而「哪些网站能抓、抓到什么算成功、
 * 失败该怎么告诉用户」这些判断散落在 Controller 里会随入口增多而悄悄分叉。
 * 这里把它收敛成单一来源，Controller 只负责参数校验与错误码映射。
 *
 * <p><b>先查后信的检测结论（2026-10-04 实测）</b>：BOSS 直聘 / 拉勾 / 智联 / 前程无忧 / 实习僧 / 牛客
 * 的岗位详情页**全部是 SPA（客户端渲染）**——服务端抓到的 HTML 里<strong>没有 JD 正文</strong>，
 * 只有一个空壳与一堆 script。因此本类<strong>不承诺「任意招聘网站都能抓」</strong>，
 * 而是诚实区分三种结果（见 {@link Outcome}）：
 * <ol>
 *   <li>{@link Outcome#OK}：抓到了足够长的正文</li>
 *   <li>{@link Outcome#SPA_OR_EMPTY}：抓到了页面但正文太短（极可能是 SPA / 需要登录 / 反爬）</li>
 *   <li>{@link Outcome#FAILED}：网络或 HTTP 层面失败</li>
 * </ol>
 * 调用方必须把后两种**分别**告知用户 —— 把「抓不到」说成「这个页面没有岗位信息」，
 * 会让用户以为是自己贴错了链接，而真实原因是他需要手动复制粘贴。
 */
@Service
public class JobPageFetcher {

    private static final Logger log = LoggerFactory.getLogger(JobPageFetcher.class);

    /** 模拟桌面浏览器；大量站点对默认 UA 直接返回 403 或空壳。 */
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/122.0 Safari/537.36";

    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 12_000;

    /** 页面体积上限：5MB。招聘页正常都在数百 KB 内，超过即视为异常（也避免打满内存）。 */
    private static final int MAX_BODY_BYTES = 5 * 1024 * 1024;

    /**
     * 送 AI 的正文上限。JD 正文本身通常 1–4K 字；这里给到 6000，
     * 是为了让**页面上的噪声**（导航/推荐位/其他岗位）不至于把真正的 JD 挤出窗口——
     * 真正的裁剪在 {@code JobAnalysisService} 的 1200 字 prompt 截断处。
     */
    private static final int MAX_TEXT_LEN = 6_000;

    /**
     * 「正文够不够」的下限。低于此值认为没抓到有效内容。
     *
     * <p>取值依据：一个真实 JD 最少也有几百字（职责 + 要求）。而 SPA 空壳页面
     * （服务端只吐出导航与 script）提纯后通常不足 300 字。500 是两者之间的保守分界，
     * 宁可多放一点噪声给 AI，也不要因为阈值太高把有效的短 JD 判死。
     */
    private static final int MIN_MEANINGFUL_TEXT = 500;

    /** 抓取结果：三态，调用方必须分别处理。 */
    public enum Outcome { OK, SPA_OR_EMPTY, FAILED }

    /**
     * 「抓不到」的细分原因。
     *
     * <p><b>为什么要在 {@link Outcome} 之外再分一层</b>：{@code SPA_OR_EMPTY} 与 {@code FAILED}
     * 只是「机器视角」的分类，对用户没有指导意义。用户真正需要知道的是
     * <b>「我现在该怎么办」</b>——而不同原因对应的动作并不相同：
     * <ul>
     *   <li>{@link #LOGIN_REQUIRED}：登录后重试**可能**有效（也可能仍无效）</li>
     *   <li>{@link #JS_RENDERED}：登录也没用，<b>只能手动复制</b>（国内招聘站的主流情况）</li>
     *   <li>{@link #BLOCKED}：站点反爬，只能手动复制</li>
     *   <li>{@link #NOT_A_JOB_PAGE}：链接可能贴错了，值得先检查链接</li>
     *   <li>{@link #NETWORK}：网络/超时，重试可能有效</li>
     * </ul>
     * 把这层信息透出去，用户才不会在「请手动粘贴」这句万能废话里反复试错。
     */
    public enum Reason { NONE, LOGIN_REQUIRED, JS_RENDERED, BLOCKED, NOT_A_JOB_PAGE, NETWORK }

    /**
     * 抓取结果载体。
     *
     * @param outcome  三态之一
     * @param reason   失败/无正文的具体原因；{@link Outcome#OK} 时为 {@link Reason#NONE}
     * @param finalUrl SSRF 校验并规范化后的 URL（**不是**用户原始输入）
     * @param text     提纯后的正文；仅 {@link Outcome#OK} 时非空
     * @param message  面向用户的失败原因；成功时为 null
     */
    public record FetchedPage(Outcome outcome, Reason reason, String finalUrl, String text, String message) {

        static FetchedPage ok(String url, String text) {
            return new FetchedPage(Outcome.OK, Reason.NONE, url, text, null);
        }

        static FetchedPage empty(String url, Reason reason, String message) {
            return new FetchedPage(Outcome.SPA_OR_EMPTY, reason, url, null, message);
        }

        static FetchedPage failed(String url, Reason reason, String message) {
            return new FetchedPage(Outcome.FAILED, reason, url, null, message);
        }
    }

    /**
     * 抓取并提纯页面正文。
     *
     * @param rawUrl 用户粘贴的原始链接
     * @return 三态结果；**从不抛异常、从不返回 null**
     */
    public FetchedPage fetch(String rawUrl) {
        // 1) SSRF 校验：协议 / 端口 / 主机名 / 解析出的全部 IP 是否公网
        SsrUrlValidator.Result valid = SsrUrlValidator.validate(rawUrl);
        if (!valid.ok) {
            return FetchedPage.failed(null, Reason.NOT_A_JOB_PAGE, valid.message);
        }
        String url = valid.normalizedUrl;

        // 2) 抓取。followRedirects(false) 是安全要求而非偏好：
        //    若能跟随 302，攻击者可用一个公网域名把我们重定向到内网地址，绕过第 1 步的全部校验。
        final Document doc;
        try {
            doc = Jsoup.connect(url)
                    .userAgent(UA)
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .timeout(Math.max(CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS))
                    .maxBodySize(MAX_BODY_BYTES)
                    .followRedirects(false)
                    .ignoreContentType(true)
                    .get();
        } catch (SocketTimeoutException e) {
            return FetchedPage.failed(url, Reason.NETWORK,
                    "访问该链接超时。请确认它能在浏览器里正常打开；若可以，说明该站点对程序访问较慢或被限速，请手动复制正文。");
        } catch (org.jsoup.HttpStatusException e) {
            int code = e.getStatusCode();
            if (code == 401) {
                return FetchedPage.failed(url, Reason.LOGIN_REQUIRED,
                        "该页面需要登录后才能查看（HTTP 401）。请先在浏览器里登录该网站，再手动复制岗位正文粘贴进来。");
            }
            if (code == 403) {
                return FetchedPage.failed(url, Reason.BLOCKED,
                        "该网站拒绝了程序访问（HTTP 403）——这类站点会屏蔽自动抓取。"
                                + "请在浏览器里打开岗位页，全选正文后手动粘贴。");
            }
            if (code == 404) {
                return FetchedPage.failed(url, Reason.NOT_A_JOB_PAGE,
                        "链接返回 404，岗位可能已下架，或链接复制不完整");
            }
            if (code == 429) {
                return FetchedPage.failed(url, Reason.BLOCKED,
                        "该网站对访问频率做了限制（HTTP 429），请稍后重试，或直接手动复制正文。");
            }
            return FetchedPage.failed(url, Reason.NETWORK, "该链接返回 HTTP " + code + "，无法读取内容");
        } catch (org.jsoup.UnsupportedMimeTypeException e) {
            return FetchedPage.failed(url, Reason.NOT_A_JOB_PAGE,
                    "这个链接指向的不是网页（可能是文件下载或接口地址），请粘贴岗位详情页的网址");
        } catch (Exception e) {
            log.warn("岗位链接抓取失败 url={} err={}", url, e.toString());
            return FetchedPage.failed(url, Reason.NETWORK, "无法访问该链接，请确认它可公开访问后重试");
        }

        // 3) 提纯：只取可见文本，剔除 script/style/标签
        String text = toPlainText(doc);
        if (text == null || text.length() < MIN_MEANINGFUL_TEXT) {
            Reason reason = classifyEmptyPage(doc, url);
            log.info("岗位链接正文过短（{} 字），reason={} url={}",
                    text == null ? 0 : text.length(), reason, url);
            return FetchedPage.empty(url, reason, emptyPageMessage(reason));
        }

        // 3.5) 长度够了，但**未必是岗位页**。2026-10-04 实测：实习僧首页能抓到 3890 字，
        //      内容却是「微信扫码登录 / 学生账号登录 / 切换城市」——把这种文本送进 AI，
        //      模型很可能「热情地」编出一份并不存在的 JD。这正是本项目红线要防的。
        //      因此这里做一次 JD 特征校验：没有职责/要求类的信号，就判定不是岗位页。
        if (!looksLikeJobPage(text)) {
            log.info("岗位链接正文够长（{} 字）但缺少 JD 特征词，判定为非岗位页 url={}", text.length(), url);
            return FetchedPage.empty(url, Reason.NOT_A_JOB_PAGE, emptyPageMessage(Reason.NOT_A_JOB_PAGE));
        }

        // 4) 截断（不静默：超长时记日志，便于回溯为什么 AI 没看到某段内容）
        if (text.length() > MAX_TEXT_LEN) {
            log.info("岗位链接正文超长，截断至 {} 字（原文 {} 字）url={}", MAX_TEXT_LEN, text.length(), url);
            text = text.substring(0, MAX_TEXT_LEN);
        }
        return FetchedPage.ok(url, text);
    }

    /**
     * HTML → 纯文本。
     *
     * <p><b>为什么不用 {@code Jsoup.clean(html, Safelist.none())}</b>（2026-10-04 实测推翻）：
     * 它会把文本**重新转义**——{@code R&D} 变成 {@code R&amp;D}、{@code <技术栈>} 变成
     * {@code &lt;技术栈&gt;}——这些转义串会被原样送进 AI 的 prompt，模型看到的是乱码式实体。
     *
     * <p><b>为什么也不能只用 {@code wholeText()}</b>（同一轮实测推翻）：jsoup 只在
     * {@code <br>} / {@code <p>} 等少数标签处换行，**{@code <div>} 兄弟节点会被直接粘连**——
     * 实测 {@code <div>岗位职责</div><div>负责后端开发</div>} 得到
     * {@code "岗位职责负责后端开发"}。JD 里「职责」「要求」几乎都用 div/li 排版，
     * 粘连后模型难以区分条目边界。
     *
     * <p>现方案：**在块级元素前手工插入换行**（{@code prepend("\n")}）再取 {@code wholeText()}。
     * 既不转义，又保住版面结构。
     */
    private static String toPlainText(Document doc) {
        if (doc == null) return null;
        Document copy = doc.clone();
        // 这些标签要么是代码、要么是不可见内容；其文本会稀释真正的 JD。
        // 尤其 <script>：SPA 页面常把整份内联状态（大段 JSON）塞在里面。
        for (String tag : List.of("script", "style", "noscript", "svg", "iframe", "template")) {
            copy.select(tag).remove();
        }
        // 块级元素前插入换行：jsoup 的 wholeText() 只认 <br>，不认 <div>/<li> 的块级语义
        for (String tag : List.of("div", "p", "li", "tr", "section", "article", "header",
                "footer", "h1", "h2", "h3", "h4", "h5", "h6")) {
            for (var el : copy.select(tag)) {
                el.prepend("\n");
            }
        }
        org.jsoup.nodes.Element body = copy.body() != null ? copy.body() : copy;
        String text = body.wholeText();
        text = text.replace('\u00A0', ' ')
                .replaceAll("[ \\t]+", " ")
                .replaceAll("(?m)^[ \\t]+", "")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * 正文是否「像一份岗位描述」。
     *
     * <p><b>为什么需要这道校验</b>（2026-10-04 实测）：实习僧首页被抓到 3890 字，
     * 内容却是登录入口与城市切换——长度门槛挡不住它。若直接送 AI 提取 JD，
     * 模型面对「不是 JD 的文本」最可能的反应不是报错，而是**编一份出来**。
     *
     * <p><b>判据刻意宽松</b>：只要求命中任意一个 JD 特征词。宁可放过一些噪声页
     * （AI 拿到噪声顶多提取不准，用户能看到原文并自行判断），
     * 也不要误杀一份措辞不典型的真实 JD——误杀的代价是用户明明贴对了却被告知「这不是岗位页」，
     * 那种挫败感比提取不准严重得多。
     *
     * <p>中英文双语判据：本项目用户既看国内岗位也看海外岗位。
     */
    private static boolean looksLikeJobPage(String text) {
        if (text == null || text.isEmpty()) return false;
        String lower = text.toLowerCase();
        // 英文：职责/要求/资格类的高频词
        String[] enSignals = {
                "responsibilit", "requirement", "qualification", "what you'll do",
                "what you will do", "you will", "we are looking for", "job description",
                "about the role", "preferred", "experience with", "you'll bring"
        };
        for (String s : enSignals) {
            if (lower.contains(s)) return true;
        }
        // 中文：岗位职责 / 任职要求 / 岗位要求 等板块标题或其变体
        String[] zhSignals = {
                "岗位职责", "工作职责", "职位描述", "岗位描述", "任职要求", "岗位要求",
                "职位要求", "任职资格", "工作内容", "岗位内容", "职责描述", "应聘要求",
                "我们希望", "你将负责", "您将负责", "加分项", "优先考虑"
        };
        for (String s : zhSignals) {
            if (text.contains(s)) return true;
        }
        return false;
    }

    /**
     * 正文过短时，进一步判断「为什么」。
     *
     * <p><b>判据选择说明</b>：这里只能看「静态 HTML 的特征」，无法真的执行 JS，
     * 所以判定是**启发式**的、可能误判。设计上遵循「宁可说轻、不要说错」：
     * 拿不准时归入 {@link Reason#JS_RENDERED}（覆盖面最广、动作也最明确——手动复制），
     * 不轻易断言「需要登录」或「链接贴错」，因为那两种说法会误导用户去改一个本来没问题的东西。
     *
     * @see #looksLikeLoginWall 登录墙特征
     * @see #looksLikeSpaShell  SPA 空壳特征
     */
    private static Reason classifyEmptyPage(Document doc, String url) {
        if (doc == null) return Reason.NETWORK;
        // 登录墙：出现密码框 / 明确的登录文案
        if (looksLikeLoginWall(doc)) return Reason.LOGIN_REQUIRED;
        // SPA 空壳：正文极少但 script 很多，或存在常见前端框架挂载点
        if (looksLikeSpaShell(doc)) return Reason.JS_RENDERED;
        // 有相当长度的正文却不足门槛 —— 更像「这个页面不是岗位页」（如首页/列表页）
        String rawText = doc.text();
        if (rawText != null && rawText.length() > 1_500) return Reason.NOT_A_JOB_PAGE;
        // 拿不准：JS_RENDERED 的指导动作（手动复制）对任何情况都不会错
        return Reason.JS_RENDERED;
    }

    /** 页面是否像登录墙：有密码输入框，或出现典型登录提示语。 */
    private static boolean looksLikeLoginWall(Document doc) {
        if (!doc.select("input[type=password]").isEmpty()) return true;
        String t = doc.text();
        if (t == null) return false;
        String lower = t.toLowerCase();
        return lower.contains("请先登录") || lower.contains("登录后查看")
                || lower.contains("登录后可见") || lower.contains("sign in to view")
                || lower.contains("log in to view") || lower.contains("请登录后");
    }

    /**
     * 页面是否像 SPA 空壳：DOM 里 script 体积占比高，或存在常见框架挂载点。
     *
     * <p>SPA 的特征是：服务端吐出的 HTML 几乎只有 {@code <div id="app">} 与一大坨 script，
     * 真正的 JD 由浏览器执行 JS 后再拉接口填充 —— 服务端永远读不到。
     */
    private static boolean looksLikeSpaShell(Document doc) {
        int scriptChars = 0;
        for (var s : doc.select("script")) scriptChars += s.data().length();
        int bodyChars = doc.body() != null ? doc.body().html().length() : doc.html().length();
        // script 占 DOM 一半以上，且总量可观 → 典型的「数据在 JS 里、不在 HTML 里」
        if (bodyChars > 2_000 && scriptChars * 2 > bodyChars) return true;
        // 常见前端框架挂载点
        return doc.select("#app, #root, #__next, [data-reactroot], #__nuxt").size() > 0
                && doc.text().length() < MIN_MEANINGFUL_TEXT;
    }

    /** 把分型翻译成「用户读完就知道下一步做什么」的话。 */
    private static String emptyPageMessage(Reason reason) {
        return switch (reason) {
            case LOGIN_REQUIRED ->
                    "这个页面需要登录才能看到岗位正文。请在浏览器里登录该网站、打开岗位页，"
                            + "然后全选正文粘贴到下面的输入框。";
            case JS_RENDERED ->
                    "没能读到岗位正文——该网站的内容是浏览器动态加载的（服务端只能拿到空壳页面），"
                            + "这是国内主流招聘 App 页面的普遍情况。请在浏览器里打开岗位页，全选正文后粘贴进来。";
            case NOT_A_JOB_PAGE ->
                    "这个链接抓到的似乎不是某个岗位的详情页（更像首页或列表页）。"
                            + "请打开具体岗位的详情页再复制它的网址。";
            default ->
                    "没能从这个链接里读到岗位正文。请改用「手动复制粘贴」："
                            + "在浏览器里打开岗位页，全选正文后粘贴到下面的输入框。";
        };
    }

    /** 供测试与日志使用的可读状态列表（避免测试里写魔法字符串）。 */
    public static List<String> outcomeNames() {
        List<String> names = new ArrayList<>();
        for (Outcome o : Outcome.values()) names.add(o.name());
        return names;
    }
}
