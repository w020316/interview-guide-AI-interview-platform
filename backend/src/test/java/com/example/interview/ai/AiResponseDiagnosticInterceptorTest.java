package com.example.interview.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 上游响应诊断拦截器测试
 *
 * 回归背景（2026-09-19 真机验证）：AI 全链路失败时日志只有
 * `RestClientException: Error while extracting response ... content type [application/json]`，
 * 完全看不出是「密钥失效」「模型名错误」还是「额度耗尽」。
 *
 * 实测根因链（三层）：
 * 1. 上游以 **HTTP 401** 返回可读错误体 {"error":{"message":"Invalid token (request id: ...)"}}；
 * 2. Spring 默认传输层 HttpURLConnection 遇 401 + 流式 POST 抛
 *    `HttpRetryException: cannot retry due to server authentication, in streaming mode`，
 *    **不读也不留响应体**；
 * 3. 拦截器最初挂在 fallbackChatModel 的入参 builder 上，而节点用的是 clone()，
 *    当时判断是 clone() 不继承 requestInterceptors → 拦截器从未被调用。
 *    ⚠️ **2026-09-26 更正（第三轮）**：该判断**无法复现** —— 本项目实际解析的
 *    spring-web 6.1.21 上，{@code clone()} 会保留拦截器列表（用本地 HttpServer 实测，
 *    见 {@code config/RestClientBuilderCloneExperiment}）。真实原因更可能是「挂在了
 *    另一个 builder 实例上」（{@code RestClient.Builder} 是原型作用域，每次注入都是新实例）。
 *    结论不变（**在每个真正被使用的 builder 上显式挂载**），但别再拿「clone 丢拦截器」
 *    去推断其他问题，那会导致误诊。
 *
 * 本类覆盖：错误识别、正常体不误判、分级（配置类故障 vs 可自愈抖动）、
 * 以及「body 必须可被下游完整读取」这一核心不破坏性约束。
 */
class AiResponseDiagnosticInterceptorTest {

    private final AiResponseDiagnosticInterceptor interceptor = new AiResponseDiagnosticInterceptor();

    private HttpRequest req() {
        return new org.springframework.http.HttpRequest() {
            @Override public org.springframework.http.HttpMethod getMethod() { return org.springframework.http.HttpMethod.POST; }
            @Override public URI getURI() { return URI.create("https://apihub.agnes-ai.com/v1/chat/completions"); }
            @Override public org.springframework.http.HttpHeaders getHeaders() { return new org.springframework.http.HttpHeaders(); }
        };
    }

    @Test
    @DisplayName("识别上游错误体：HTTP 200 + {\"error\":{\"message\":\"Invalid token\"}}")
    void detectsInvalidTokenBody() {
        String body = "{\"error\":{\"code\":\"\",\"message\":\"Invalid token (request id: abc)\","
                + "\"type\":\"AgnesAI_error\"}}";
        assertThat(AiResponseDiagnosticInterceptor.looksLikeError(body)).isTrue();
    }

    @Test
    @DisplayName("识别额度/模型类错误体")
    void detectsQuotaAndModelErrors() {
        assertThat(AiResponseDiagnosticInterceptor.looksLikeError(
                "{\"error\":{\"message\":\"insufficient balance\"}}")).isTrue();
        assertThat(AiResponseDiagnosticInterceptor.looksLikeError(
                "{\"error\":{\"message\":\"The model does not exist\"}}")).isTrue();
        assertThat(AiResponseDiagnosticInterceptor.looksLikeError(
                "{\"error\":{\"message\":\"rate limit exceeded\"}}")).isTrue();
        assertThat(AiResponseDiagnosticInterceptor.looksLikeError(
                "{\"error\":{\"message\":\"Unauthorized\"}}")).isTrue();
    }

    @Test
    @DisplayName("正常响应体不被误判（避免正常请求产生日志噪音）")
    void ignoresNormalBody() {
        String normal = "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"choices\":"
                + "[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"你好\"}}]}";
        assertThat(AiResponseDiagnosticInterceptor.looksLikeError(normal)).isFalse();
        assertThat(AiResponseDiagnosticInterceptor.looksLikeError("")).isFalse();
        assertThat(AiResponseDiagnosticInterceptor.looksLikeError(null)).isFalse();
    }

    @Test
    @DisplayName("响应体被完整保留供下游读取（不破坏正常反序列化）")
    void preservesBodyForDownstream() throws IOException {
        String normal = "{\"id\":\"chatcmpl-1\",\"choices\":[]}";
        MockClientHttpResponse original = new MockClientHttpResponse(
                normal.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);

        ClientHttpRequestExecution exec = (request, body) -> original;
        ClientHttpResponse wrapped = interceptor.intercept(req(), new byte[0], exec);

        // 关键：下游仍能读到完整 body，否则 Spring AI 的正常解析会被拦截器搞坏
        String readBack = new String(wrapped.getBody().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(readBack).isEqualTo(normal);
        assertThat(wrapped.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("错误响应体同样可被下游读取（诊断不影响既有错误传播路径）")
    void preservesErrorBodyForDownstream() throws IOException {
        String errBody = "{\"error\":{\"message\":\"Invalid token\"}}";
        MockClientHttpResponse original = new MockClientHttpResponse(
                errBody.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);

        ClientHttpRequestExecution exec = (request, body) -> original;
        ClientHttpResponse wrapped = interceptor.intercept(req(), new byte[0], exec);

        assertThat(new String(wrapped.getBody().readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo(errBody);
    }

    @Test
    @DisplayName("snippet：超长响应体被截断、换行被压平，保证日志单行可读")
    void snippetTruncatesAndFlattens() {
        String longBody = ("x".repeat(2000) + "\n\nyy").replace("\n", "\n");
        String s = AiResponseDiagnosticInterceptor.snippet(longBody);
        assertThat(s.length()).isLessThanOrEqualTo(603);
        assertThat(s).doesNotContain("\n");
        assertThat(s).endsWith("...");
    }

    // ───────────────────────── 分级：配置类故障 vs 可自愈抖动 ─────────────────────────

    @Test
    @DisplayName("配置类故障：密钥失效/欠费/模型下线 → 判为需人工处理（ERROR 级别）")
    void classifiesConfigFaults() {
        assertThat(AiResponseDiagnosticInterceptor.isConfigFault(
                "{\"error\":{\"message\":\"Invalid token (request id: 202609191553414084817114b4wgvaY)\"}}"))
                .isTrue();
        assertThat(AiResponseDiagnosticInterceptor.isConfigFault(
                "{\"error\":{\"message\":\"Incorrect API key provided\"}}")).isTrue();
        assertThat(AiResponseDiagnosticInterceptor.isConfigFault(
                "{\"error\":{\"message\":\"You exceeded your current quota\"}}")).isTrue();
        assertThat(AiResponseDiagnosticInterceptor.isConfigFault(
                "{\"error\":{\"message\":\"insufficient balance\"}}")).isTrue();
        assertThat(AiResponseDiagnosticInterceptor.isConfigFault(
                "{\"error\":{\"message\":\"model_not_found\"}}")).isTrue();
    }

    @Test
    @DisplayName("可自愈抖动：限流/临时不可用 → 不判为配置故障（WARN 级别即可）")
    void doesNotClassifyTransientIssuesAsConfigFault() {
        assertThat(AiResponseDiagnosticInterceptor.isConfigFault(
                "{\"error\":{\"message\":\"rate limit exceeded, please retry\"}}")).isFalse();
        assertThat(AiResponseDiagnosticInterceptor.isConfigFault(
                "{\"error\":{\"message\":\"upstream temporarily unavailable\"}}")).isFalse();
        assertThat(AiResponseDiagnosticInterceptor.isConfigFault("")).isFalse();
        assertThat(AiResponseDiagnosticInterceptor.isConfigFault(null)).isFalse();
    }

    @Test
    @DisplayName("非 2xx 状态即使响应体无错误关键字，也走诊断日志（覆盖空响应体场景）")
    void logsOnNon2xxEvenWithEmptyBody() throws IOException {
        MockClientHttpResponse original = new MockClientHttpResponse(
                new byte[0], HttpStatus.UNAUTHORIZED);

        ClientHttpRequestExecution exec = (request, body) -> original;
        ClientHttpResponse wrapped = interceptor.intercept(req(), new byte[0], exec);

        // 关键不变式：拦截器绝不能吞掉状态码，否则上层无法判定失败
        assertThat(wrapped.getStatusCode().value()).isEqualTo(401);
        assertThat(wrapped.getBody().readAllBytes()).isEmpty();
    }

    @Test
    @DisplayName("状态码读取抛异常时不吞异常：必须向上传播，不得让调用方误以为成功")
    void propagatesStatusCodeFailure() {
        // 不用 MockClientHttpResponse 覆写（它的 getStatusCode 未声明 IOException，无法覆盖），
        // 直接给一个最小实现的坏响应，模拟传输层/协议层故障
        ClientHttpResponse broken = new ClientHttpResponse() {
            @Override public org.springframework.http.HttpStatusCode getStatusCode() throws IOException {
                throw new IOException("simulated transport failure");
            }
            @Override public String getStatusText() { return ""; }
            @Override public void close() { }
            @Override public java.io.InputStream getBody() { return java.io.InputStream.nullInputStream(); }
            @Override public org.springframework.http.HttpHeaders getHeaders() {
                return new org.springframework.http.HttpHeaders();
            }
        };

        ClientHttpRequestExecution exec = (request, body) -> broken;

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> interceptor.intercept(req(), new byte[0], exec))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("simulated transport failure");
    }
}
