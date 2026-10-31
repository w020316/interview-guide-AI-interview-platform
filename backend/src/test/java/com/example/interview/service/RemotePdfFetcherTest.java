package com.example.interview.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RemotePdfFetcher} 的真实 HTTP 行为测试。
 *
 * <p>用 JDK 内置 HttpServer 在本机起服务（不依赖外网），覆盖：
 * 正常 PDF / 分享页 HTML / 跳转 / 需要登录 / 假 PDF / 文件名解析 / 流式大小上限。
 * 测试直接调用 fetcher，不经过 SSRF 校验（该层由 Controller 与 SsrUrlValidator 单测覆盖）。
 */
@DisplayName("RemotePdfFetcher 分享链接下载")
class RemotePdfFetcherTest {

    private static HttpServer server;
    private static String baseUrl;

    private static final byte[] PDF = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".getBytes(StandardCharsets.US_ASCII);

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/files/resume.pdf", exchange -> {
            String encoded = URLEncoder.encode("吴宇威-Java.pdf", StandardCharsets.UTF_8).replace("+", "%20");
            exchange.getResponseHeaders().add("Content-Type", "application/pdf");
            exchange.getResponseHeaders().add("Content-Disposition",
                    "attachment; filename*=UTF-8''" + encoded);
            send(exchange, 200, PDF);
        });
        server.createContext("/files/fallback.pdf", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            send(exchange, 200, PDF);
        });
        server.createContext("/share/page.html", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            send(exchange, 200, "<html><body>在线简历</body></html>".getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/files/resume.pdf");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/private", exchange -> {
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });
        server.createContext("/fake.pdf", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/pdf");
            send(exchange, 200, "这不是 PDF".getBytes(StandardCharsets.UTF_8));
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void send(com.sun.net.httpserver.HttpExchange exchange, int status, byte[] body)
            throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Test
    @DisplayName("合法 PDF：返回字节与 UTF-8 文件名")
    void fetch_pdf_ok() {
        RemotePdfFetcher.FetchedPdf result = new RemotePdfFetcher().fetch(baseUrl + "/files/resume.pdf");

        assertThat(result.content()).isEqualTo(PDF);
        assertThat(result.fileName()).isEqualTo("吴宇威-Java.pdf");
    }

    @Test
    @DisplayName("没有 Content-Disposition 时用 URL 末段做文件名")
    void fetch_filenameFromUrl() {
        RemotePdfFetcher.FetchedPdf result = new RemotePdfFetcher().fetch(baseUrl + "/files/fallback.pdf");

        assertThat(result.fileName()).isEqualTo("fallback.pdf");
    }

    @Test
    @DisplayName("分享页返回 HTML：明确提示这不是 PDF")
    void fetch_htmlPage_hasClearMessage() {
        assertThatThrownBy(() -> new RemotePdfFetcher().fetch(baseUrl + "/share/page.html"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("链接返回的是网页，不是 PDF 文件。请打开该页面下载 PDF 后从本地上传");
    }

    @Test
    @DisplayName("重定向不跟随，提示使用最终直链（防绕过 SSRF 校验）")
    void fetch_redirect_rejected() {
        assertThatThrownBy(() -> new RemotePdfFetcher().fetch(baseUrl + "/redirect"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("链接发生了跳转（HTTP 302），请使用最终的 PDF 直链");
    }

    @Test
    @DisplayName("403：提示需要登录或链接失效")
    void fetch_forbidden_hasClearMessage() {
        assertThatThrownBy(() -> new RemotePdfFetcher().fetch(baseUrl + "/private"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("链接需要登录或已失效，请在平台内重新获取公开分享链接");
    }

    @Test
    @DisplayName("后缀是 pdf 但内容不是：按 magic bytes 拒绝")
    void fetch_fakePdf_rejected() {
        assertThatThrownBy(() -> new RemotePdfFetcher().fetch(baseUrl + "/fake.pdf"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("链接内容不是合法的 PDF 文件");
    }

    @Test
    @DisplayName("流式读取超过 10MB 立即中断（不依赖 Content-Length）")
    void readLimited_stopsAtMaxBytes() {
        byte[] oversized = new byte[RemotePdfFetcher.MAX_BYTES + 1];

        assertThatThrownBy(() -> RemotePdfFetcher.readLimited(new ByteArrayInputStream(oversized)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("PDF 大小不能超过 10MB");
    }
}
