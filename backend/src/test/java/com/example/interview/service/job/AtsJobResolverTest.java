package com.example.interview.service.job;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ATS 岗位解析器测试（v1.58.0）。
 *
 * <p><b>测试策略</b>：不依赖任何外部站点的存活——URL 识别的正则、HTML 实体解码、
 * 标签剥离、slug 美化全部是**纯函数**，直接测。</p>
 *
 * <p>为什么这很重要：本功能的可靠性完全建立在「能正确从链接里认出 slug + jobId」上。
 * 识别错一个字符就会去请求错误的 board，拿到空结果或别人的岗位。
 * 而这一类 bug 在真实网络下**很难复现**（依赖具体链接），只能靠离线用例锁死。
 */
class AtsJobResolverTest {

    private final AtsJobResolver resolver = new AtsJobResolver();

    // ── 域名识别 ──

    @Test
    @DisplayName("域名识别：Ashby / Greenhouse 被认作受支持 ATS")
    void isSupportedAts_true() {
        assertThat(AtsJobResolver.isSupportedAts("https://jobs.ashbyhq.com/ramp/abc")).isTrue();
        assertThat(AtsJobResolver.isSupportedAts("https://job-boards.greenhouse.io/stripe/jobs/1")).isTrue();
        assertThat(AtsJobResolver.isSupportedAts("https://boards.greenhouse.io/x/jobs/1")).isTrue();
    }

    @Test
    @DisplayName("域名识别：其他站点不被误认（否则会去请求不存在的 ATS 接口）")
    void isSupportedAts_false() {
        assertThat(AtsJobResolver.isSupportedAts("https://www.zhipin.com/job_detail/x")).isFalse();
        assertThat(AtsJobResolver.isSupportedAts("https://jobs.apple.com/x")).isFalse();
        // 关键：伪造的子域不得命中——contains() 会漏掉这种，必须用精确主机名比对
        assertThat(AtsJobResolver.isSupportedAts("https://jobs.ashbyhq.com.evil.com/ramp"))
                .as("子域混淆不应被视为受支持 ATS（contains 实现的典型漏洞）")
                .isFalse();
        assertThat(AtsJobResolver.isSupportedAts("https://boards.greenhouse.io.evil.com/x/jobs/1"))
                .as("Greenhouse 子域混淆同样应被拒")
                .isFalse();
        assertThat(AtsJobResolver.isSupportedAts("https://evil.com/?u=jobs.ashbyhq.com"))
                .as("URL 参数里含受信域名不应命中")
                .isFalse();
        assertThat(AtsJobResolver.isSupportedAts(null)).isFalse();
        assertThat(AtsJobResolver.isSupportedAts("")).isFalse();
    }

    // ── URL 反解（正则）──

    @Test
    @DisplayName("Ashby 链接反解出 slug 与 uuid")
    void ashbyUrlParsing() throws Exception {
        Pattern p = getPattern("ASHBY_PAGE");
        Matcher m = p.matcher("https://jobs.ashbyhq.com/ramp/34413f8d-26bf-4bbc-8ade-eb309a0e2245");
        assertThat(m.matches()).isTrue();
        assertThat(m.group(1)).isEqualTo("ramp");
        assertThat(m.group(2)).isEqualTo("34413f8d-26bf-4bbc-8ade-eb309a0e2245");
    }

    @Test
    @DisplayName("Ashby 链接：带查询参数/尾斜杠也能解析（用户复制时常带 ?utm=…）")
    void ashbyUrlParsing_withNoise() throws Exception {
        Pattern p = getPattern("ASHBY_PAGE");
        Matcher m = p.matcher("https://jobs.ashbyhq.com/linear/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
                + "?utm_source=linkedin&ref=x");
        assertThat(m.matches()).isTrue();
        assertThat(m.group(1)).isEqualTo("linear");
        assertThat(m.group(2)).isEqualTo("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    }

    @Test
    @DisplayName("Greenhouse 链接反解出 slug 与数字 id")
    void greenhouseUrlParsing() throws Exception {
        Pattern p = getPattern("GH_PAGE");
        Matcher m = p.matcher("https://job-boards.greenhouse.io/stripe/jobs/8172508");
        assertThat(m.matches()).isTrue();
        assertThat(m.group(1)).isEqualTo("stripe");
        assertThat(m.group(2)).isEqualTo("8172508");

        // 老域名形态同样支持
        Matcher legacy = p.matcher("https://boards.greenhouse.io/acme/jobs/123456");
        assertThat(legacy.matches()).isTrue();
        assertThat(legacy.group(1)).isEqualTo("acme");
    }

    // ── HTML 实体解码 ──

    @Test
    @DisplayName("实体解码：Greenhouse 的 content 是转义 HTML，必须先解实体再剥标签")
    void unescapeHtml_decodes() {
        assertThat(AtsJobResolver.unescapeHtml("&lt;h2&gt;Who we are&lt;/h2&gt;"))
                .isEqualTo("<h2>Who we are</h2>");
        assertThat(AtsJobResolver.unescapeHtml("R&amp;D")).isEqualTo("R&D");
        assertThat(AtsJobResolver.unescapeHtml("a &lt; b &amp;&amp; c &gt; d"))
                .isEqualTo("a < b && c > d");
        assertThat(AtsJobResolver.unescapeHtml("&nbsp;空格")).contains("空格");
        // &amp; 必须最后解，否则 "&amp;lt;" 会被解成 "<"（双重解码 bug）
        assertThat(AtsJobResolver.unescapeHtml("&amp;lt;"))
                .as("&amp;lt; 应解成 &lt; 而不是 <（顺序错误的典型症状）")
                .isEqualTo("&lt;");
    }

    // ── 标签剥离 ──

    @Test
    @DisplayName("剥标签：块级元素之间保留换行（与 JobPageFetcher 同一教训）")
    void stripHtml_keepsBlockBoundaries() {
        String html = "<h2>岗位职责</h2><p>负责后端开发</p><ul><li>熟悉 Java</li><li>熟悉 SQL</li></ul>";
        String text = AtsJobResolver.stripHtml(html);
        assertThat(text).contains("岗位职责").contains("负责后端开发")
                .contains("熟悉 Java").contains("熟悉 SQL");
        assertThat(text).as("块级元素之间应有换行").contains("\n");
        assertThat(text).as("不得残留标签").doesNotContain("<h2>").doesNotContain("<li>");
        assertThat(text).as("列表项应有项目符号，便于模型识别条目").contains("•");
    }

    @Test
    @DisplayName("剥标签：<br> 变换行，连续空白压缩")
    void stripHtml_brAndWhitespace() {
        String text = AtsJobResolver.stripHtml("第一行<br>第二行<br/>第三行  空格");
        assertThat(text).contains("第一行").contains("第二行").contains("第三行");
        assertThat(text).as("br 应产生换行").contains("\n");
        assertThat(text).as("不应有连续空格").doesNotContain("  ");
    }

    @Test
    @DisplayName("剥标签：script/style 内容不应混入（JD 里偶有内联样式）")
    void stripHtml_removesScriptStyle() {
        String html = "<style>.a{color:red}</style><p>正文</p><script>var x=1;</script>";
        String text = AtsJobResolver.stripHtml(html);
        assertThat(text).contains("正文");
        assertThat(text).as("style 内容应被剥掉").doesNotContain("color:red");
        assertThat(text).as("script 内容应被剥掉").doesNotContain("var x");
    }

    @Test
    @DisplayName("剥标签：空/纯标签输入返回空串，不返回 null")
    void stripHtml_edgeCases() {
        assertThat(AtsJobResolver.stripHtml(null)).isEmpty();
        assertThat(AtsJobResolver.stripHtml("")).isEmpty();
        assertThat(AtsJobResolver.stripHtml("<div></div>")).isEmpty();
    }

    // ── slug 美化 ──

    @Test
    @DisplayName("slug 美化：用作公司名兜底（Greenhouse 有时不返回 company_name）")
    void prettifySlug_converts() {
        assertThat(AtsJobResolver.prettifySlug("ramp")).isEqualTo("Ramp");
        assertThat(AtsJobResolver.prettifySlug("stripe")).isEqualTo("Stripe");
        assertThat(AtsJobResolver.prettifySlug("acme-corp")).isEqualTo("Acme corp");
        assertThat(AtsJobResolver.prettifySlug("")).isEmpty();
        assertThat(AtsJobResolver.prettifySlug(null)).isEmpty();
    }

    // ── 不支持的链接必须安全返回 empty（而非抛异常）──

    @Test
    @DisplayName("非 ATS 链接返回 empty（调用方据此退化到 HTML 抓取），且从不抛异常")
    void resolve_nonAtsReturnsEmpty() {
        assertThat(resolver.resolve("https://www.zhipin.com/job_detail/x")).isEmpty();
        assertThat(resolver.resolve("https://jobs.apple.com/en-us/details/1")).isEmpty();
        assertThat(resolver.resolve("")).isEmpty();
        assertThat(resolver.resolve(null)).isEmpty();
        // 畸形输入也不应炸
        assertThat(resolver.resolve("not a url at all")).isEmpty();
        assertThat(resolver.resolve("https://jobs.ashbyhq.com/")).isEmpty();
        assertThat(resolver.resolve("https://job-boards.greenhouse.io/x")).isEmpty();
    }

    // ── 反射取私有常量正则 ──

    private static Pattern getPattern(String fieldName) throws Exception {
        java.lang.reflect.Field f = AtsJobResolver.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        return (Pattern) f.get(null);
    }
}
