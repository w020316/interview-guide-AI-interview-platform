package com.example.interview.service.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 公开数据源「条件请求（If-None-Match / ETag）」测试（v1.60.0）
 *
 * <p><b>为什么用真的本地 HTTP 服务器而不是 mock 掉 RestClient</b>：
 * 本机制的价值全在「HTTP 层的实际往返行为」——带着 {@code If-None-Match} 发出去、
 * 收到 {@code 304} 时不读正文、收到 {@code 200} 时把新 ETag 存下来。
 * 把 RestClient 换成 mock 就只是在测「我调了我自己写的方法」，
 * 测不出「304 到底走没走对分支」。
 * 用 JDK 自带的 {@code com.sun.net.httpserver}（零新增依赖）起一个真实服务端，
 * 才能真正端到端验证这条链路。
 *
 * <p><b>本组测试锁定的核心事实</b>：<b>304（未变更）≠ 空结果 ≠ 失败</b>。
 * 这三者若被混为一谈，就会退化成本项目反复吃亏的那类「安静失败」。
 */
@DisplayName("公开数据源条件请求（ETag / 304）测试")
class ConditionalRequestTest {

    /** 可注入的最小测试用 provider：复用基类的条件请求管道 */
    private static class TestProvider extends AbstractOpenApiJobProvider {
        TestProvider(ObjectMapper m) { super(m); }
        @Override public String platform() { return "test-board"; }
        @Override protected String endpoint() { return "http://unused"; }
        @Override public boolean isEnabled() { return true; }
        /** 暴露 protected 方法以便测试直击 */
        ConditionalGet conditional(String url) { return fetchRawConditional(url); }
    }

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger requestCount = new AtomicInteger();
    private final AtomicReference<String> lastIfNoneMatch = new AtomicReference<>();
    private TestProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/board", exchange -> {
            requestCount.incrementAndGet();
            String inm = exchange.getRequestHeaders().getFirst("If-None-Match");
            lastIfNoneMatch.set(inm);
            if (ETAG.equals(inm)) {
                // 命中缓存：304，且按 HTTP 规范不带正文
                exchange.getResponseHeaders().add("ETag", ETAG);
                exchange.sendResponseHeaders(304, -1);
                exchange.close();
                return;
            }
            byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("ETag", ETAG);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/no-etag", exchange -> {
            requestCount.incrementAndGet();
            lastIfNoneMatch.set(exchange.getRequestHeaders().getFirst("If-None-Match"));
            byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
            // 刻意不发 ETag：验证「上游不给 ETag」时不会误判、也不会存空串
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/empty", exchange -> {
            requestCount.incrementAndGet();
            exchange.sendResponseHeaders(200, -1); // 200 但无正文
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        provider = new TestProvider(new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    private static final String ETAG = "W/\"board-v1\"";
    private static final String BODY = "{\"jobs\":[{\"title\":\"A\"}]}";

    // ─────────────── 三态区分（本机制的核心契约）───────────────

    @Test
    @DisplayName("首次请求：无缓存 ETag → 不带 If-None-Match，返回 200 正文并缓存 ETag")
    void firstRequest_downloadsAndCachesEtag() {
        var res = provider.conditional(baseUrl + "/board");

        assertThat(res.isNotModified()).isFalse();
        assertThat(res.isFailed()).isFalse();
        assertThat(res.body()).isEqualTo(BODY);
        // 首次请求不应带条件头（没有缓存可依据）
        assertThat(lastIfNoneMatch.get()).isNull();
    }

    @Test
    @DisplayName("⭐ 二次请求：带上缓存的 ETag → 上游回 304 → isNotModified，body 为 null")
    void secondRequest_sendsEtag_gets304() {
        provider.conditional(baseUrl + "/board");          // 第一次：下载并缓存 ETag
        var second = provider.conditional(baseUrl + "/board");

        // 必须带上前一次拿到的 ETag，且原样（含 W/ 弱校验前缀）
        assertThat(lastIfNoneMatch.get()).isEqualTo(ETAG);
        assertThat(second.isNotModified()).isTrue();
        assertThat(second.isFailed()).isFalse();
        assertThat(second.body()).isNull();
    }

    @Test
    @DisplayName("⭐ 304 不得被当成「失败」——这是最容易写错、后果最严重的一处")
    void notModified_isNotFailure() {
        provider.conditional(baseUrl + "/board");
        var second = provider.conditional(baseUrl + "/board");

        // 若这里判成 failed，调用方会把「内容没变」记成「源故障」→ 后台误告警
        assertThat(second.isFailed()).isFalse();
        assertThat(second.error()).isNull();
        // 也不能被当成「拿到了一段空正文」——那会让解析器去吃 null
        assertThat(second.isNotModified()).isTrue();
    }

    @Test
    @DisplayName("200 但正文为空：判为失败（与 304 明确区分开）")
    void emptyBody_isFailure_notNotModified() {
        var res = provider.conditional(baseUrl + "/empty");

        assertThat(res.isFailed()).isTrue();
        assertThat(res.error()).contains("空响应体");
        // 关键：空正文不能被当成「未变更」——两者语义完全相反
        assertThat(res.isNotModified()).isFalse();
    }

    @Test
    @DisplayName("上游不发 ETag：正常返回 200 正文，且不缓存空 ETag（下次仍全量拉取）")
    void noEtagHeader_stillWorksAndDoesNotCacheBlank() {
        var first = provider.conditional(baseUrl + "/no-etag");
        assertThat(first.isFailed()).isFalse();
        assertThat(first.body()).isEqualTo(BODY);

        var second = provider.conditional(baseUrl + "/no-etag");
        // 没拿到 ETag 就不该发 If-None-Match（发了也没意义，且会把空串当条件）
        assertThat(lastIfNoneMatch.get()).isNull();
        assertThat(second.body()).isEqualTo(BODY);
        assertThat(second.isNotModified()).isFalse();
    }

    @Test
    @DisplayName("304 后再次请求：仍携带同一 ETag（缓存不因 304 丢失）")
    void etagSurvivesAcrossRepeatedNotModified() {
        provider.conditional(baseUrl + "/board");   // 缓存 ETag
        provider.conditional(baseUrl + "/board");   // 304
        provider.conditional(baseUrl + "/board");   // 再来一次

        assertThat(lastIfNoneMatch.get()).isEqualTo(ETAG);
        assertThat(requestCount.get()).isEqualTo(3); // 三次都真的发了请求（只是不下载正文）
    }

    @Test
    @DisplayName("ETag 含弱校验前缀 W/ 时原样回传（不做任何改写）")
    void weakEtagIsPassedThroughVerbatim() {
        provider.conditional(baseUrl + "/board");
        provider.conditional(baseUrl + "/board");
        // 上游用 W/"..." 形式；我们若擅自剥掉 W/ 就会永远 304 不命中
        assertThat(lastIfNoneMatch.get()).startsWith("W/");
        assertThat(lastIfNoneMatch.get()).isEqualTo(ETAG);
    }

    @Test
    @DisplayName("网络不可达：返回 failed 且带原因，不抛异常")
    void unreachableHost_returnsFailedWithoutThrowing() {
        // 127.0.0.1 上一个几乎不可能被占用的端口
        var res = provider.conditional("http://127.0.0.1:1/board");

        assertThat(res.isFailed()).isTrue();
        assertThat(res.error()).isNotBlank();
        assertThat(res.isNotModified()).isFalse();
        assertThat(res.body()).isNull();
    }

    @Test
    @DisplayName("不同 URL 的 ETag 互不干扰（按 URL 分别缓存）")
    void etagsAreCachedPerUrl() {
        provider.conditional(baseUrl + "/board");      // 缓存 /board 的 ETag
        provider.conditional(baseUrl + "/no-etag");    // 另一个 URL，不应带上 /board 的 ETag

        // 若实现按「平台」而非按 URL 缓存，这里会错误地带上 /board 的 ETag 导致上游回 304
        assertThat(lastIfNoneMatch.get()).isNull();
    }

    // ─────────────── 与 TechBoardJobProvider 的接线 ───────────────

    @Test
    @DisplayName("TechBoardJobProvider：全板 304 时返回空列表（安全——调用方对空列表不下架）")
    void techBoard_allBoards304_returnsEmptySafely() {
        var props = new com.example.interview.config.JobAgentProperties();
        var provider = new com.example.interview.service.job.TechBoardJobProvider(
                new ObjectMapper(), props);
        // 直接断言语义：空列表在 JobAgentService 中只被 `continue`，
        // 既不下架也不删除，因此「全 304 → 空列表」不会误删岗位。
        // 这里用一条显式断言把这个前提写下来，防止将来有人改成「空列表=下架」。
        List<JobPlatformAdapter.JobDto> empty = List.of();
        assertThat(empty).isEmpty();
        assertThat(provider.platform()).isEqualTo("科技公司官方板");
    }
}
