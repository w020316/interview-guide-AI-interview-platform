package com.example.interview.util;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.jsoup.HttpStatusException;
import org.jsoup.UnsupportedMimeTypeException;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PinnedHttp 抓取客户端（DNS 重绑定防护）")
class PinnedHttpTest {

    private static HttpServer server;
    private static int port;
    private static final AtomicInteger redirectTargetHits = new AtomicInteger();

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/page", ex -> send(ex, 200, "text/html; charset=utf-8",
                "<html><body><p>岗位职责</p></body></html>".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/gbk", ex -> send(ex, 200, "text/html; charset=GBK",
                "<html><body>任职要求</body></html>".getBytes(Charset.forName("GBK"))));
        server.createContext("/redirect", ex -> {
            ex.getResponseHeaders().add("Location", "/target");
            send(ex, 302, "text/html", "<p>moved</p>".getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/target", ex -> {
            redirectTargetHits.incrementAndGet();
            send(ex, 200, "text/html", "<p>target</p>".getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/forbidden", ex -> send(ex, 403, "text/html",
                "<p>denied</p>".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/json", ex -> send(ex, 200, "application/json",
                "{\"a\":1}".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/big", ex -> send(ex, 200, "text/html",
                ("<p>" + "x".repeat(10_000) + "</p>").getBytes(StandardCharsets.UTF_8)));
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    private static OkHttpClient loopbackAllowed() {
        return PinnedHttp.newBuilder(Dns.SYSTEM, PinnedHttp.blockedExceptLoopback()).build();
    }

    private static Request get(String path) {
        return new Request.Builder().url("http://127.0.0.1:" + port + path).build();
    }

    @Test
    @DisplayName("域名在连接时解析到内网地址即拦截，请求不会发出")
    void rebindingHost_blockedAtConnect() {
        Dns rebinding = host -> List.of(InetAddress.getLoopbackAddress());
        OkHttpClient client = PinnedHttp.newBuilder(rebinding, SsrUrlValidator::isBlocked).build();
        Request request = new Request.Builder().url("http://jobs.example.com:" + port + "/page").build();

        assertThatThrownBy(() -> PinnedHttp.getHtml(client, request, 1024, false, false))
                .isInstanceOf(PinnedHttp.BlockedAddressException.class);
    }

    @Test
    @DisplayName("IP 字面量不经过 Dns 钩子，由对端地址校验拦截")
    void ipLiteral_blockedByPeerCheck() {
        OkHttpClient client = PinnedHttp.newBuilder().build();

        assertThatThrownBy(() -> PinnedHttp.getHtml(client, get("/page"), 1024, false, false))
                .isInstanceOf(PinnedHttp.BlockedAddressException.class);
    }

    @Test
    @DisplayName("正常页面按 Content-Type 的字符集解析（含 GBK）")
    void parsesUtf8AndGbk() throws IOException {
        assertThat(PinnedHttp.getHtml(loopbackAllowed(), get("/page"), 1024, false, false).text())
                .isEqualTo("岗位职责");
        assertThat(PinnedHttp.getHtml(loopbackAllowed(), get("/gbk"), 1024, false, false).text())
                .isEqualTo("任职要求");
    }

    @Test
    @DisplayName("不跟随 302：返回跳转页本身，跳转目标从未被请求")
    void redirect_notFollowed() throws IOException {
        int before = redirectTargetHits.get();
        Document doc = PinnedHttp.getHtml(loopbackAllowed(), get("/redirect"), 1024, false, false);

        assertThat(doc.text()).isEqualTo("moved");
        assertThat(redirectTargetHits.get()).isEqualTo(before);
    }

    @Test
    @DisplayName("HTTP ≥400 抛 HttpStatusException 并带状态码；ignoreHttpErrors 时照常解析")
    void httpErrors() throws IOException {
        assertThatThrownBy(() -> PinnedHttp.getHtml(loopbackAllowed(), get("/forbidden"), 1024, false, false))
                .isInstanceOfSatisfying(HttpStatusException.class, e -> assertThat(e.getStatusCode()).isEqualTo(403));
        assertThat(PinnedHttp.getHtml(loopbackAllowed(), get("/forbidden"), 1024, true, false).text())
                .isEqualTo("denied");
    }

    @Test
    @DisplayName("非网页类型默认拒绝；ignoreContentType 时放行")
    void contentType() throws IOException {
        assertThatThrownBy(() -> PinnedHttp.getHtml(loopbackAllowed(), get("/json"), 1024, false, false))
                .isInstanceOf(UnsupportedMimeTypeException.class);
        assertThat(PinnedHttp.getHtml(loopbackAllowed(), get("/json"), 1024, false, true).text())
                .contains("\"a\":1");
    }

    @Test
    @DisplayName("超过上限的正文被截断而不是整页读入")
    void bodyTruncated() throws IOException {
        Document doc = PinnedHttp.getHtml(loopbackAllowed(), get("/big"), 100, false, false);

        assertThat(doc.text().length()).isLessThan(100);
    }
}
