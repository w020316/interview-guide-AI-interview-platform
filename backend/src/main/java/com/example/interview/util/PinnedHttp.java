package com.example.interview.util;

import okhttp3.Dns;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.UnsupportedMimeTypeException;
import org.jsoup.nodes.Document;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 代用户抓取外部 URL 的 HTTP 客户端工厂。
 *
 * <p>{@link SsrUrlValidator} 只在校验时解析一次 DNS；若随后用会再次解析的客户端（如 {@code Jsoup.connect}）
 * 发请求，攻击者可让域名校验时指向公网、连接时指向内网（DNS 重绑定）。这里的客户端在连接时
 * 逐个校验解析结果，并只使用通过校验的地址；主机名、Host 头与 TLS SNI 保持原域名。
 */
public final class PinnedHttp {

    private PinnedHttp() {
    }

    /** 已带好 DNS 绑定、对端校验、禁止重定向与禁用代理的构建器，调用方只需补超时 */
    public static OkHttpClient.Builder newBuilder() {
        return newBuilder(Dns.SYSTEM, SsrUrlValidator::isBlocked);
    }

    public static OkHttpClient.Builder newBuilder(Dns upstream, Predicate<InetAddress> blocked) {
        return new OkHttpClient.Builder()
                // 走代理时连接的是代理而非目标，DNS 校验就失去意义
                .proxy(Proxy.NO_PROXY)
                // 重定向目标可能绕过已完成的校验
                .followRedirects(false)
                .followSslRedirects(false)
                .dns(new PinnedPublicDns(upstream, blocked))
                // IP 字面量主机不经过 Dns 钩子，所以再校验实际连上的对端地址
                .addNetworkInterceptor(chain -> {
                    okhttp3.Connection conn = chain.connection();
                    InetAddress peer = conn == null ? null : conn.socket().getInetAddress();
                    if (peer == null || blocked.test(peer)) {
                        throw new BlockedAddressException(chain.request().url().host());
                    }
                    return chain.proceed(chain.request());
                });
    }

    /** 测试专用：允许连接本机测试服务器，其它地址仍按 SSRF 规则拦截 */
    public static Predicate<InetAddress> blockedExceptLoopback() {
        return addr -> !addr.isLoopbackAddress() && SsrUrlValidator.isBlocked(addr);
    }

    /** 解析一次并只返回通过校验的地址，连接直接使用这些地址，不再二次解析 */
    public static final class PinnedPublicDns implements Dns {
        private final Dns upstream;
        private final Predicate<InetAddress> blocked;

        public PinnedPublicDns(Dns upstream, Predicate<InetAddress> blocked) {
            this.upstream = upstream;
            this.blocked = blocked;
        }

        @Override
        public List<InetAddress> lookup(String hostname) throws UnknownHostException {
            List<InetAddress> addresses = upstream.lookup(hostname);
            for (InetAddress addr : addresses) {
                if (blocked.test(addr)) {
                    throw new BlockedAddressException(hostname);
                }
            }
            return addresses;
        }
    }

    public static final class BlockedAddressException extends UnknownHostException {
        public BlockedAddressException(String host) {
            super("blocked address for host " + host);
        }
    }

    private static final Pattern XML_CONTENT_TYPE = Pattern.compile("(application|text)/\\w*\\+?xml.*");

    /**
     * GET 一个页面并按 Jsoup 原有语义解析：状态码 ≥400 抛 {@link HttpStatusException}（除非 ignoreHttpErrors），
     * 非 text/xml 类型抛 {@link UnsupportedMimeTypeException}（除非 ignoreContentType），
     * 正文超过 maxBodyBytes 时截断而非报错。3xx 不跟随，按其响应体解析。
     */
    public static Document getHtml(OkHttpClient client, Request request, int maxBodyBytes,
                                   boolean ignoreHttpErrors, boolean ignoreContentType) throws IOException {
        String url = request.url().toString();
        try (Response response = client.newCall(request).execute()) {
            int status = response.code();
            if (!ignoreHttpErrors && (status < 200 || status >= 400)) {
                throw new HttpStatusException("HTTP error fetching URL", status, url);
            }
            ResponseBody body = response.body();
            MediaType mediaType = body == null ? null : body.contentType();
            String contentType = response.header("Content-Type");
            if (!ignoreContentType && contentType != null && !contentType.startsWith("text/")
                    && !XML_CONTENT_TYPE.matcher(contentType).matches()) {
                throw new UnsupportedMimeTypeException("Unhandled content type", contentType, url);
            }
            byte[] bytes;
            if (body == null) {
                bytes = new byte[0];
            } else {
                try (InputStream in = body.byteStream()) {
                    bytes = in.readNBytes(maxBodyBytes);
                }
            }
            Charset charset = mediaType == null ? null : mediaType.charset();
            return Jsoup.parse(new ByteArrayInputStream(bytes), charset == null ? null : charset.name(), url);
        }
    }
}
