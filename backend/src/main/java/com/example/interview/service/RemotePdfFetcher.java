package com.example.interview.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从用户授权的分享链接下载 PDF。
 *
 * <p>只接受**直链 PDF**：平台分享页通常是 HTML（需要登录、带前端路由），
 * 这类链接会得到明确提示，不会被当成 PDF 硬解析。SSRF 校验由调用方先用
 * {@link com.example.interview.util.SsrUrlValidator} 完成。
 *
 * <p>限额与上传接口保持一致（10MB），超限在读取过程中即时中断，
 * 不依赖 Content-Length（分块传输可能没有该头）。
 */
@Component
public class RemotePdfFetcher {

    private static final Logger log = LoggerFactory.getLogger(RemotePdfFetcher.class);

    /** 与文件上传一致的上限 */
    public static final int MAX_BYTES = 10 * 1024 * 1024;

    private static final int READ_BUFFER = 8 * 1024;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // 禁止跟随重定向：重定向目标可能绕过已完成的 SSRF 校验
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** 下载结果：PDF 字节与展示用文件名 */
    public record FetchedPdf(byte[] content, String fileName) {
    }

    public FetchedPdf fetch(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "application/pdf,application/octet-stream;q=0.9,*/*;q=0.5")
                .build();

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException e) {
            throw new IllegalArgumentException("链接访问超时，请确认它可公开访问后重试");
        } catch (IOException e) {
            log.warn("分享链接下载失败 url={} error={}", url, e.getMessage());
            throw new IllegalArgumentException("链接无法访问，请确认它可公开访问后重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PDF 下载被中断");
        }

        int status = response.statusCode();
        if (status >= 300 && status < 400) {
            throw new IllegalArgumentException("链接发生了跳转（HTTP " + status + "），请使用最终的 PDF 直链");
        }
        if (status == 401 || status == 403) {
            throw new IllegalArgumentException("链接需要登录或已失效，请在平台内重新获取公开分享链接");
        }
        if (status == 404 || status == 410) {
            throw new IllegalArgumentException("链接不存在或已过期，请在平台内重新获取分享链接");
        }
        if (status < 200 || status >= 300) {
            throw new IllegalArgumentException("下载 PDF 失败（HTTP " + status + "）");
        }

        String contentType = response.headers().firstValue("content-type")
                .orElse("").toLowerCase(Locale.ROOT);
        if (contentType.startsWith("text/") || contentType.contains("html")
                || contentType.contains("json") || contentType.contains("xml")) {
            throw new IllegalArgumentException(
                    "链接返回的是网页，不是 PDF 文件。请打开该页面下载 PDF 后从本地上传");
        }

        java.util.OptionalLong declaredLength = response.headers().firstValueAsLong("content-length");
        if (declaredLength.isPresent() && declaredLength.getAsLong() > MAX_BYTES) {
            throw new IllegalArgumentException("PDF 大小不能超过 10MB");
        }

        byte[] content = readLimited(response.body());
        if (content.length < 5 || content[0] != '%' || content[1] != 'P' || content[2] != 'D'
                || content[3] != 'F' || content[4] != '-') {
            throw new IllegalArgumentException("链接内容不是合法的 PDF 文件");
        }
        return new FetchedPdf(content, resolveFileName(url, response));
    }

    static byte[] readLimited(InputStream in) {
        try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[READ_BUFFER];
            long total = 0;
            int read;
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > MAX_BYTES) {
                    throw new IllegalArgumentException("PDF 大小不能超过 10MB");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalArgumentException("下载 PDF 时连接中断，请重试");
        }
    }

    private static final Pattern FILENAME_STAR = Pattern.compile(
            "filename\\*\\s*=\\s*UTF-8''([^;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILENAME_PLAIN = Pattern.compile(
            "filename\\s*=\\s*\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE);

    /** 文件名仅用于展示与历史记录；解析格式由 magic bytes 决定，不信任后缀 */
    static String resolveFileName(String url, HttpResponse<?> response) {
        String name = null;
        String disposition = response.headers().firstValue("content-disposition").orElse("");
        Matcher star = FILENAME_STAR.matcher(disposition);
        if (star.find()) {
            try {
                name = URLDecoder.decode(star.group(1).trim(), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                name = null;
            }
        }
        if (name == null || name.isBlank()) {
            Matcher plain = FILENAME_PLAIN.matcher(disposition);
            if (plain.find()) {
                name = plain.group(1).trim();
            }
        }
        if (name == null || name.isBlank()) {
            String path = URI.create(url).getPath();
            name = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
            try {
                name = URLDecoder.decode(name, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                // 保留原样
            }
        }
        name = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        if (name.isBlank()) {
            name = "resume.pdf";
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            name = name + ".pdf";
        }
        if (name.length() > 120) {
            name = name.substring(0, 116) + ".pdf";
        }
        return name;
    }
}
