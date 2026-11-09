package com.example.interview.service;

import com.example.interview.util.SsrUrlValidator;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从用户授权的分享链接下载 PDF。
 *
 * <p>只接受**直链 PDF**：平台分享页通常是 HTML，这类链接会得到明确提示，不会被当成 PDF 硬解析。
 * 调用方先用 {@link SsrUrlValidator} 做 URL 级校验；本类在建立连接时再按解析结果逐个校验 IP，
 * 连接只使用通过校验的地址，从而堵住 DNS 重绑定（校验时公网、连接时内网）的窗口。
 * 主机名、Host 头与 TLS SNI 均保持原域名，证书校验不受影响。
 *
 * <p>限额与上传接口保持一致（10MB），超限在读取过程中即时中断，不依赖 Content-Length。
 */
@Component
public class RemotePdfFetcher {

    private static final Logger log = LoggerFactory.getLogger(RemotePdfFetcher.class);

    /** 与文件上传一致的上限 */
    public static final int MAX_BYTES = 10 * 1024 * 1024;

    private static final int READ_BUFFER = 8 * 1024;

    private final OkHttpClient httpClient;

    public RemotePdfFetcher() {
        this(SsrUrlValidator::isBlocked);
    }

    RemotePdfFetcher(Predicate<InetAddress> blocked) {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .callTimeout(Duration.ofSeconds(20))
                // 禁止跟随重定向：重定向目标可能绕过已完成的 SSRF 校验
                .followRedirects(false)
                .followSslRedirects(false)
                .dns(new PinnedPublicDns(blocked))
                // IP 字面量主机不经过 Dns 钩子，所以再校验实际连上的对端地址
                .addNetworkInterceptor(chain -> {
                    okhttp3.Connection conn = chain.connection();
                    InetAddress peer = conn == null ? null : conn.socket().getInetAddress();
                    if (peer == null || blocked.test(peer)) {
                        throw new BlockedAddressException(chain.request().url().host());
                    }
                    return chain.proceed(chain.request());
                })
                .build();
    }

    /** 测试专用：允许连接本机测试服务器，其它地址仍按 SSRF 规则拦截 */
    static RemotePdfFetcher allowingLoopbackForTest() {
        return new RemotePdfFetcher(addr -> !addr.isLoopbackAddress() && SsrUrlValidator.isBlocked(addr));
    }

    /** 下载结果：PDF 字节与展示用文件名 */
    public record FetchedPdf(byte[] content, String fileName) {
    }

    /** 解析一次并只返回通过校验的地址，连接直接使用这些地址，不再二次解析 */
    static final class PinnedPublicDns implements Dns {
        private final Predicate<InetAddress> blocked;

        PinnedPublicDns(Predicate<InetAddress> blocked) {
            this.blocked = blocked;
        }

        @Override
        public List<InetAddress> lookup(String hostname) throws UnknownHostException {
            List<InetAddress> addresses = Dns.SYSTEM.lookup(hostname);
            for (InetAddress addr : addresses) {
                if (blocked.test(addr)) {
                    throw new BlockedAddressException(hostname);
                }
            }
            return addresses;
        }
    }

    static final class BlockedAddressException extends UnknownHostException {
        BlockedAddressException(String host) {
            super("blocked address for host " + host);
        }
    }

    public FetchedPdf fetch(String url) {
        Request request = new Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "application/pdf,application/octet-stream;q=0.9,*/*;q=0.5")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            int status = response.code();
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

            String contentType = response.header("content-type", "").toLowerCase(Locale.ROOT);
            if (contentType.startsWith("text/") || contentType.contains("html")
                    || contentType.contains("json") || contentType.contains("xml")) {
                throw new IllegalArgumentException(
                        "链接返回的是网页，不是 PDF 文件。请打开该页面下载 PDF 后从本地上传");
            }

            ResponseBody body = response.body();
            if (body == null) {
                throw new IllegalArgumentException("链接内容不是合法的 PDF 文件");
            }
            if (body.contentLength() > MAX_BYTES) {
                throw new IllegalArgumentException("PDF 大小不能超过 10MB");
            }

            byte[] content = readLimited(body.byteStream());
            if (content.length < 5 || content[0] != '%' || content[1] != 'P' || content[2] != 'D'
                    || content[3] != 'F' || content[4] != '-') {
                throw new IllegalArgumentException("链接内容不是合法的 PDF 文件");
            }
            return new FetchedPdf(content, resolveFileName(url, response.header("content-disposition", "")));
        } catch (BlockedAddressException e) {
            log.warn("分享链接解析到内网地址，已拦截 url={}", url);
            throw new IllegalArgumentException("不支持访问内网地址");
        } catch (InterruptedIOException e) {
            throw new IllegalArgumentException("链接访问超时，请确认它可公开访问后重试");
        } catch (IOException e) {
            log.warn("分享链接下载失败 url={} error={}", url, e.getMessage());
            throw new IllegalArgumentException("链接无法访问，请确认它可公开访问后重试");
        }
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
    static String resolveFileName(String url, String disposition) {
        String name = null;
        if (disposition == null) {
            disposition = "";
        }
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
