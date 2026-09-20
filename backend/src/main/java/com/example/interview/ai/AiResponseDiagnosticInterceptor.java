package com.example.interview.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * AI 上游响应诊断拦截器
 *
 * ── 为什么需要它（2026-09-19 真机验证发现）─────────────────────────────
 * 现象：AI 全链路失败时，用户只看到「AI 服务暂时不可用」，日志里只有
 *       `RestClientException: Error while extracting response ... content type [application/json]`
 *       —— 这一句话对排障毫无价值。
 *
 * 根因：上游 AI 网关（如 Agnes）在鉴权失败时返回 **HTTP 401** + 可读错误体：
 *         {"error":{"code":"","message":"Invalid token (request id: ...)","type":"AgnesAI_error"}}
 *       而 Spring 默认的 {@code SimpleClientHttpRequestFactory} 底层是 JDK
 *       {@code HttpURLConnection}：遇到 401 且请求体为流式（POST）时无法自动重试，
 *       直接抛 {@code HttpRetryException: cannot retry due to server authentication, in streaming mode}，
 *       **既不读取也不保留响应体**。Spring AI 侧因此只剩「Error while extracting response」，
 *       异常 cause 链实测为 0 层，「密钥失效」这个明确需要人工介入的配置故障被彻底隐藏。
 *
 * 本拦截器在响应进入 Spring AI 解析逻辑**之前**读一份响应体快照，命中错误关键字时
 * 记录 WARN 日志，让「Invalid token / insufficient balance / model not found」这类
 * 配置故障在运维日志里直接可见。
 *
 * ── 前提（P0-04，2026-09-19）─────────────────────────────────────────
 * 光有拦截器不够：{@code HttpURLConnection} 的 401 分支**根本进不到拦截器**。
 * 传输层已切换为 JDK {@code HttpClient}（见 AiConfig#aiRequestFactory），
 * 401 作为正常响应返回、响应体完整可读，本拦截器才有意义。
 *
 * ── 实现要点 ─────────────────────────────────────────────────────────
 * - 用 {@link BufferingClientHttpResponseWrapper} 包裹原始响应：把 body 读进内存后
 *   重新包装返回，**下游仍能正常读取**（否则流被消费一次就空了，会破坏正常请求）。
 * - 只对「看起来不是正常 chat completion」的响应做日志（JSON 顶层出现 error 字段，
 *   或非 2xx）。正常响应不产生任何日志噪音。
 * - 任何异常都吞掉：诊断逻辑绝不能影响主流程。
 */
public class AiResponseDiagnosticInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AiResponseDiagnosticInterceptor.class);

    /** 单次记录的最大响应体长度，避免大响应撑爆日志 */
    private static final int MAX_SNIPPET = 600;

    /** 判定为「上游错误体」的关键字（大小写敏感的低层匹配 + 小写匹配双保险） */
    private static final String[] ERROR_HINTS = {
            "\"error\"", "Invalid token", "invalid_api_key", "insufficient",
            "balance", "quota", "rate limit", "model_not_found", "not found",
            "Unauthorized", "unauthorized", "authentication"
    };

    /**
     * 需要人工介入的「配置类故障」特征词。
     *
     * <p>命中这些词的 4xx 属于**必须改配置才能恢复**（密钥失效/欠费/模型下线），
     * 与「上游临时抖动、重试即可」区分开：前者 ERROR 级别（要求运维介入），
     * 后者 WARN 级别（可能自愈）。这个分级让告警规则可以直接按级别配置。
     */
    private static final String[] CONFIG_FAULT_HINTS = {
            "invalid token", "invalid_api_key", "incorrect api key", "unauthorized",
            "authentication", "insufficient", "balance", "quota", "model_not_found"
    };

    @Override
    public ClientHttpResponse intercept(org.springframework.http.HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        ClientHttpResponse response = execution.execute(request, body);

        // 读取状态码本身也可能抛异常（如上游 TLS/协议层故障）。
        // 这里必须放宽到 Throwable 且**不吞掉**——异常要继续抛给上层，
        // 因为此时没有任何可用的响应对象能返回（旧实现在此漏了保护，
        // 导致 HttpRetryException 从本行直接穿出，既没日志也破坏了语义）。
        int status;
        try {
            status = response.getStatusCode().value();
        } catch (Throwable t) {
            log.warn("AI 上游响应状态码读取失败（疑似连接层问题）：url={}, 原因={}",
                    safeUri(request), t.toString());
            throw t instanceof IOException io ? io : new IOException(t);
        }

        try {
            // 只在「HTTP 层不是成功」或「疑似错误体」时才缓冲，避免正常路径多一次内存拷贝
            boolean suspiciousStatus = status < 200 || status >= 300;
            byte[] snapshot;
            try {
                snapshot = response.getBody().readAllBytes();
            } catch (Exception e) {
                // 读不出就不干预，直接放行原响应（它已被消费，但这是异常路径，主流程本就失败）
                log.warn("AI 上游响应体读取失败：url={}, status={}, 原因={}",
                        safeUri(request), status, e.toString());
                return response;
            }

            String text = new String(snapshot, StandardCharsets.UTF_8);
            if (looksLikeError(text) || suspiciousStatus) {
                if (isConfigFault(text)) {
                    // 配置类故障：不改配置永远好不了，用 ERROR 让告警能直接命中
                    log.error("AI 上游返回配置类故障（需人工处理，通常是 API Key 失效/欠费/模型下线）："
                                    + "url={}, status={}, body={}",
                            safeUri(request), status, snippet(text));
                } else {
                    log.warn("AI 上游返回异常响应：url={}, status={}, body={}",
                            safeUri(request), status, snippet(text));
                }
            }

            // 重新包装，保证下游（Spring AI 的 Jackson 反序列化）仍能完整读取
            return new BufferingClientHttpResponseWrapper(response, snapshot);
        } catch (Exception e) {
            log.debug("AI 响应诊断拦截器异常（已忽略，不影响主流程）：{}", e.getMessage());
            return response;
        }
    }

    /** URI 取值本身也不该影响主流程，兜底返回占位串 */
    private static String safeUri(org.springframework.http.HttpRequest request) {
        try {
            return String.valueOf(request.getURI());
        } catch (Throwable t) {
            return "<unknown>";
        }
    }

    /** 响应体是否含上游错误标记 */
    static boolean looksLikeError(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase();
        for (String hint : ERROR_HINTS) {
            if (text.contains(hint) || lower.contains(hint.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    /** 是否属于「必须人工改配置」的故障（区别于可自愈的抖动） */
    static boolean isConfigFault(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase();
        for (String hint : CONFIG_FAULT_HINTS) {
            if (lower.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    /** 截断超长响应体，并压掉换行让日志单行可读 */
    static String snippet(String text) {
        if (text == null) {
            return "";
        }
        String one = text.replaceAll("\\s+", " ").trim();
        return one.length() > MAX_SNIPPET ? one.substring(0, MAX_SNIPPET) + "..." : one;
    }
}

/**
 * 把已读入内存的字节重新包装成可重复读取的响应。
 *
 * <p>标准库的 {@code org.springframework.http.client.BufferingClientHttpResponseWrapper}
 * 需要构造参数，而 Spring Framework 6.1 的该实现只提供无参（需自行 setBody）或
 * 直接包装 body 的形式。这里自建一个最小实现，避免版本差异带来的编译问题。
 */
class BufferingClientHttpResponseWrapper implements ClientHttpResponse {

    private final ClientHttpResponse delegate;
    private final byte[] body;

    BufferingClientHttpResponseWrapper(ClientHttpResponse delegate, byte[] body) {
        this.delegate = delegate;
        this.body = body;
    }

    @Override
    public org.springframework.http.HttpStatusCode getStatusCode() throws IOException {
        return delegate.getStatusCode();
    }

    @Override
    public String getStatusText() throws IOException {
        return delegate.getStatusText();
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public InputStream getBody() {
        return new ByteArrayInputStream(body);
    }

    @Override
    public org.springframework.http.HttpHeaders getHeaders() {
        return delegate.getHeaders();
    }
}
