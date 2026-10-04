package com.example.interview.service.job;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 岗位链接抓取器测试（v1.58.0，竞品 #14）。
 *
 * <p><b>测试策略</b>：SSRF 拒绝路径与异常分类**不需要真实网络**——前者由 {@code SsrUrlValidator}
 * 同步判定，后者靠受控异常。真正需要网络的是「抓到的页面怎么提纯」，那部分用反射直接测
 * 私有方法 {@code toPlainText}，喂入构造好的 HTML —— 这样测试不依赖任何外部站点是否存活，
 * 也就不会随别人改版而腐烂。
 */
class JobPageFetcherTest {

    private final JobPageFetcher fetcher = new JobPageFetcher();

    // ── SSRF 拒绝：必须**在发起任何网络请求之前**就返回 FAILED ──

    @Test
    @DisplayName("内网/回环/元数据地址在校验阶段即被拒，且不暴露未校验的 URL")
    void blockedUrls_rejectedBeforeAnyRequest() {
        String[] blocked = {
                "http://127.0.0.1/admin",
                "http://localhost/jobs/1",
                "http://192.168.1.1/job",
                "http://169.254.169.254/latest/meta-data/",
                "http://metadata.google.internal/x",
        };
        for (String url : blocked) {
            JobPageFetcher.FetchedPage page = fetcher.fetch(url);
            assertThat(page.outcome())
                    .as("URL 应被拒绝：%s", url)
                    .isEqualTo(JobPageFetcher.Outcome.FAILED);
            // 被拒时不得回显 URL —— 那是一个未被校验、可能被用于钓鱼/误导的字符串
            assertThat(page.finalUrl()).as("被拒时不应返回 URL：%s", url).isNull();
            assertThat(page.message()).isNotBlank();
            assertThat(page.text()).isNull();
        }
    }

    @Test
    @DisplayName("非 http(s) 协议与敏感端口被拒")
    void badProtocolAndPort_rejected() {
        assertThat(fetcher.fetch("ftp://example.com/job").outcome())
                .isEqualTo(JobPageFetcher.Outcome.FAILED);
        assertThat(fetcher.fetch("javascript:alert(1)").outcome())
                .isEqualTo(JobPageFetcher.Outcome.FAILED);
        assertThat(fetcher.fetch("http://192.0.2.1:6379/").outcome())
                .isEqualTo(JobPageFetcher.Outcome.FAILED);
        assertThat(fetcher.fetch("http://192.0.2.1:3306/").outcome())
                .isEqualTo(JobPageFetcher.Outcome.FAILED);
    }

    @Test
    @DisplayName("空 / null 输入不抛异常，返回 FAILED 而非 null")
    void blankInput_returnsFailedNotNull() {
        for (String input : new String[] { null, "", "   " }) {
            JobPageFetcher.FetchedPage page = fetcher.fetch(input);
            assertThat(page).as("输入=%s 时不应返回 null", input).isNotNull();
            assertThat(page.outcome()).isEqualTo(JobPageFetcher.Outcome.FAILED);
            assertThat(page.message()).isNotBlank();
        }
    }

    @Test
    @DisplayName("无论成功失败都**从不抛异常**（调用方无需 try-catch 兜底）")
    void neverThrows() {
        // 这些输入覆盖协议错误、DNS 失败、端口非法、超长串等边界
        String[] inputs = {
                "not a url", "http://", "https://", "http://[::1]/x",
                "http://" + "a".repeat(600) + ".com/x", "://x",
        };
        for (String input : inputs) {
            JobPageFetcher.FetchedPage page = fetcher.fetch(input);
            assertThat(page).as("输入=%s", input).isNotNull();
            assertThat(page.outcome()).isNotNull();
        }
    }

    // ── 三态语义：必须可区分，否则调用方无法给用户正确提示 ──

    @Test
    @DisplayName("三态枚举齐全，且每条失败文案都不为空（用户要能看懂）")
    void outcomesAreDistinguishable() {
        assertThat(JobPageFetcher.Outcome.values())
                .containsExactly(JobPageFetcher.Outcome.OK,
                        JobPageFetcher.Outcome.SPA_OR_EMPTY,
                        JobPageFetcher.Outcome.FAILED);

        JobPageFetcher.FetchedPage spa = JobPageFetcher.FetchedPage.empty(
                "https://x.com/j", JobPageFetcher.Reason.JS_RENDERED,
                "该页面是动态渲染的，请手动复制粘贴正文");
        assertThat(spa.outcome()).isEqualTo(JobPageFetcher.Outcome.SPA_OR_EMPTY);
        assertThat(spa.reason()).isEqualTo(JobPageFetcher.Reason.JS_RENDERED);
        assertThat(spa.message())
                .as("SPA 文案必须告诉用户「改用手动粘贴」，而不是让他以为链接贴错了")
                .contains("粘贴");
        assertThat(spa.text()).isNull();

        JobPageFetcher.FetchedPage failed = JobPageFetcher.FetchedPage.failed(
                null, JobPageFetcher.Reason.NOT_A_JOB_PAGE, "原因");
        assertThat(failed.outcome()).isEqualTo(JobPageFetcher.Outcome.FAILED);
        assertThat(failed.reason()).isEqualTo(JobPageFetcher.Reason.NOT_A_JOB_PAGE);
        assertThat(failed.message()).isEqualTo("原因");

        JobPageFetcher.FetchedPage ok = JobPageFetcher.FetchedPage.ok("https://x.com/j", "正文");
        assertThat(ok.outcome()).isEqualTo(JobPageFetcher.Outcome.OK);
        assertThat(ok.reason()).as("成功时无失败原因").isEqualTo(JobPageFetcher.Reason.NONE);
        assertThat(ok.text()).isEqualTo("正文");
        assertThat(ok.message()).as("成功时不应有错误文案").isNull();
    }

    // ── HTML 提纯：走反射直接测，不依赖真实站点 ──

    @Test
    @DisplayName("提纯：script/style 内容不混入正文（否则 SPA 内联 JSON 会挤掉真 JD）")
    void toPlainText_stripsScriptAndStyle() throws Exception {
        String html = "<html><head><style>.a{color:red}</style>"
                + "<script>var state={\"jobs\":[1,2,3],\"blob\":\""
                + "x".repeat(2000) + "\"}</script></head>"
                + "<body><h1>高级 Java 工程师</h1><p>负责后端服务开发</p></body></html>";

        String text = invokeToPlainText(html);

        assertThat(text).contains("高级 Java 工程师").contains("负责后端服务开发");
        assertThat(text).as("style 内容不应出现").doesNotContain("color:red");
        assertThat(text).as("script 内容不应出现").doesNotContain("var state");
        assertThat(text).as("script 内的大段 JSON 不应残留").doesNotContain("x".repeat(100));
    }

    @Test
    @DisplayName("提纯：压缩连续空白与空行，但保留行结构（职责/要求分行可读）")
    void toPlainText_normalizesWhitespace() throws Exception {
        String html = "<body><p>职责一</p><p>职责二</p>"
                + "<div>多个&nbsp;空格</div>"
                + "<br><br><br><br>"
                + "<p>要求</p></body>";

        String text = invokeToPlainText(html);

        assertThat(text).contains("职责一").contains("职责二").contains("要求");
        assertThat(text).as("nbsp 应转成普通空格").doesNotContain("\u00A0");
        assertThat(text).as("不应出现 3 个以上连续换行").doesNotContain("\n\n\n");
        assertThat(text).doesNotContain("  ");   // 连续空格已压缩
    }

    @Test
    @DisplayName("提纯：HTML 实体被**解码**而非二次转义（曾用 Jsoup.clean 导致 R&D 变成 R&amp;D）")
    void toPlainText_decodesEntities() throws Exception {
        String html = "<body><p>薪资 20k&ndash;35k &amp; 期权</p>"
                + "<p>&lt;技术栈&gt; Java / Spring</p></body>";

        String text = invokeToPlainText(html);

        assertThat(text).contains("20k").contains("35k").contains("期权");
        assertThat(text).contains("Java").contains("Spring");
        assertThat(text).as("&amp; 必须解码成 &，否则模型看到的是实体串")
                .contains("&").doesNotContain("&amp;");
        assertThat(text).as("&lt;/&gt; 必须解码成尖括号")
                .contains("<技术栈>").doesNotContain("&lt;").doesNotContain("&gt;");
        assertThat(text).as("标签本身不应残留").doesNotContain("<p>");
    }

    @Test
    @DisplayName("提纯：块级元素之间保留换行（曾用 Jsoup.clean 导致相邻段落粘成一行）")
    void toPlainText_keepsBlockBoundaries() throws Exception {
        // 这正是被测出的第二个缺陷：clean() 会把「期权」与下一行粘成「期权<技术栈>」
        String html = "<body><div>岗位职责</div><div>负责后端开发</div>"
                + "<div>任职要求</div><div>3 年以上经验</div></body>";

        String text = invokeToPlainText(html);

        assertThat(text).as("块级元素之间应有换行，不能全部粘成一行")
                .contains("\n");
        assertThat(text).as("不应出现「职责负责后端开发」这种跨块粘连")
                .doesNotContain("岗位职责负责后端开发");
    }

    @Test
    @DisplayName("提纯：纯标签 / 空文档 → null（不返回空串，让判空只有一种口径）")
    void toPlainText_emptyReturnsNull() throws Exception {
        assertThat(invokeToPlainText("")).isNull();
        assertThat(invokeToPlainText("<body><script>var a=1</script></body>")).isNull();
        assertThat(invokeToPlainText("<html><body>   <br>  </body></html>")).isNull();
    }

    // ── 岗位页特征校验（v1.58.0 补）：挡住「够长但不是 JD」的页面 ──

    @Test
    @DisplayName("岗位页校验：真 JD（中英）放行")
    void looksLikeJobPage_acceptsRealJd() throws Exception {
        assertThat(invokeLooksLikeJobPage("岗位职责：负责后端服务开发。任职要求：3年以上 Java 经验。"))
                .as("中文典型 JD 必须放行").isTrue();
        assertThat(invokeLooksLikeJobPage("Responsibilities: build services. Requirements: 5+ years."))
                .as("英文典型 JD 必须放行").isTrue();
        assertThat(invokeLooksLikeJobPage("About the role: you will design APIs. What you'll bring: Go."))
                .as("英文口语化 JD 必须放行").isTrue();
        assertThat(invokeLooksLikeJobPage("职位描述 工作内容：参与推荐系统研发。加分项：大数据经验。"))
                .as("含「加分项」的 JD 必须放行").isTrue();
    }

    @Test
    @DisplayName("岗位页校验：登录页 / 首页 / 导航页被拦下（实测实习僧首页能抓 3890 字却无 JD）")
    void looksLikeJobPage_rejectsNonJd() throws Exception {
        assertThat(invokeLooksLikeJobPage("微信扫码登录 学生账号登录 短信登录 密码登录 切换城市 热门职位"))
                .as("登录页不是岗位页").isFalse();
        assertThat(invokeLooksLikeJobPage("Home About Contact Privacy Terms Cookie Settings Subscribe"))
                .as("站点导航页不是岗位页").isFalse();
        assertThat(invokeLooksLikeJobPage("共计 1000 个热门职位 企业入口 职位百科 校招 实习 切换城市"))
                .as("职位列表页不是岗位页").isFalse();
    }

    @Test
    @DisplayName("岗位页校验：判据刻意宽松——只命中一个信号词即可，避免误杀措辞不典型的真 JD")
    void looksLikeJobPage_isDeliberatelyLenient() throws Exception {
        // 只有一句「优先考虑」，其余全是公司介绍 —— 仍然放行（宁可放过噪声，不误杀真 JD）
        assertThat(invokeLooksLikeJobPage("我们是一家专注云原生的公司。优先考虑有开源贡献的同学。"))
                .isTrue();
        // 空/极短文本为 false（这种情况本就该走空页分支）
        assertThat(invokeLooksLikeJobPage("")).isFalse();
        assertThat(invokeLooksLikeJobPage(null)).isFalse();
    }

    // ── 空页分型：决定给用户哪一句「下一步该做什么」 ──

    @Test
    @DisplayName("空页分型：登录墙 → LOGIN_REQUIRED")
    void classifyEmptyPage_loginWall() throws Exception {
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(
                "<html><body><form><input type=password></form><div>请先登录后查看</div></body></html>");
        assertThat(invokeClassifyEmptyPage(doc)).isEqualTo(JobPageFetcher.Reason.LOGIN_REQUIRED);
    }

    @Test
    @DisplayName("空页分型：SPA 空壳（script 占主体 + 框架挂载点）→ JS_RENDERED")
    void classifyEmptyPage_spaShell() throws Exception {
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(
                "<html><head><script>var s='" + "x".repeat(5000)
                        + "';</script></head><body><div id=app></div></body></html>");
        assertThat(invokeClassifyEmptyPage(doc)).isEqualTo(JobPageFetcher.Reason.JS_RENDERED);
    }

    @Test
    @DisplayName("空页分型：正文够长却不足门槛 → NOT_A_JOB_PAGE（更像列表/首页）")
    void classifyEmptyPage_notAJobPage() throws Exception {
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(
                "<html><body>" + "<div>岗位列表条目内容</div>".repeat(200) + "</body></html>");
        assertThat(invokeClassifyEmptyPage(doc)).isEqualTo(JobPageFetcher.Reason.NOT_A_JOB_PAGE);
    }

    @Test
    @DisplayName("空页分型：每种分型的用户文案都不为空，且都指向可执行的下一步")
    void emptyPageMessages_areActionable() throws Exception {
        Method m = JobPageFetcher.class.getDeclaredMethod("emptyPageMessage", JobPageFetcher.Reason.class);
        m.setAccessible(true);
        for (JobPageFetcher.Reason r : JobPageFetcher.Reason.values()) {
            if (r == JobPageFetcher.Reason.NONE) continue;
            String msg = (String) m.invoke(null, r);
            assertThat(msg).as("分型 %s 的文案不能为空", r).isNotBlank();
            assertThat(msg.length()).as("分型 %s 的文案应足够具体（>20 字）", r).isGreaterThan(20);
        }
    }

    // ── 私有方法反射调用 ──

    private static String invokeToPlainText(String html) throws Exception {
        org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html);
        Method m = JobPageFetcher.class.getDeclaredMethod("toPlainText", org.jsoup.nodes.Document.class);
        m.setAccessible(true);
        return (String) m.invoke(null, doc);
    }

    private static boolean invokeLooksLikeJobPage(String text) throws Exception {
        Method m = JobPageFetcher.class.getDeclaredMethod("looksLikeJobPage", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, text);
    }

    private static JobPageFetcher.Reason invokeClassifyEmptyPage(org.jsoup.nodes.Document doc) throws Exception {
        Method m = JobPageFetcher.class.getDeclaredMethod(
                "classifyEmptyPage", org.jsoup.nodes.Document.class, String.class);
        m.setAccessible(true);
        return (JobPageFetcher.Reason) m.invoke(null, doc, "https://x.com/j");
    }
}
