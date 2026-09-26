package com.example.interview.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DisableThinkingInterceptor} 测试。
 *
 * <p>回归背景（2026-09-26 线上实测 P1-02）：智谱 GLM 默认开启思考模式，
 * {@code reasoning_content} 吃光输出预算 → **HTTP 200 但 content 为空**；
 * 而 {@code FallbackChatModel} 把「空正文」判为该节点不可用并继续降级，
 * 于是**兜底节点每次都被静默跳过**，跨厂商容灾退化为单点。
 */
class DisableThinkingInterceptorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final DisableThinkingInterceptor interceptor = new DisableThinkingInterceptor();

    /** 捕获拦截器实际转发出去的 body 与 Content-Length */
    private record Forwarded(byte[] body, long contentLength) {
    }

    private Forwarded forward(String json) throws IOException {
        AtomicReference<byte[]> capturedBody = new AtomicReference<>();
        AtomicReference<Long> capturedLen = new AtomicReference<>();
        // 注意：headers 必须是**同一个实例**。若 getHeaders() 每次 new 一个，
        // 拦截器对 wrapper 设置的 Content-Length 就丢了，断言会读到 -1（测试桩问题，非实现问题）。
        HttpHeaders headers = new HttpHeaders();
        ClientHttpRequestExecution exec = (request, body) -> {
            capturedBody.set(body);
            capturedLen.set(request.getHeaders().getContentLength());
            return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
        };
        HttpRequest req = new HttpRequest() {
            @Override public HttpMethod getMethod() { return HttpMethod.POST; }
            @Override public URI getURI() { return URI.create("https://open.bigmodel.cn/api/paas/v4/chat/completions"); }
            @Override public HttpHeaders getHeaders() { return headers; }
        };
        interceptor.intercept(req, json.getBytes(StandardCharsets.UTF_8), exec);
        return new Forwarded(capturedBody.get(), capturedLen.get());
    }

    @Test
    @DisplayName("普通请求体：注入 thinking=disabled，并同步修正 Content-Length")
    void injectsThinkingAndFixesContentLength() throws IOException {
        String original = "{\"model\":\"glm-4.7-flash\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        Forwarded f = forward(original);

        JsonNode node = MAPPER.readTree(f.body());
        assertThat(node.path("thinking").path("type").asText()).isEqualTo("disabled");
        // 原有字段必须原样保留
        assertThat(node.path("model").asText()).isEqualTo("glm-4.7-flash");
        assertThat(node.path("messages")).hasSize(1);
        // 关键：请求体变长后 Content-Length 必须跟着变，否则会被截断或读越界
        assertThat(f.body().length).isGreaterThan(original.getBytes(StandardCharsets.UTF_8).length);
        assertThat(f.contentLength()).isEqualTo(f.body().length);
    }

    @Test
    @DisplayName("调用方已显式指定 thinking：原样放行，不覆盖")
    void respectsExplicitThinking() throws IOException {
        String original = "{\"model\":\"glm-4.7-flash\",\"thinking\":{\"type\":\"enabled\"}}";
        Forwarded f = forward(original);

        assertThat(new String(f.body(), StandardCharsets.UTF_8)).isEqualTo(original);
        assertThat(MAPPER.readTree(f.body()).path("thinking").path("type").asText()).isEqualTo("enabled");
    }

    @Test
    @DisplayName("非 JSON 请求体：原样放行，零副作用")
    void passesThroughNonJson() throws IOException {
        String original = "not-a-json-body";
        Forwarded f = forward(original);
        assertThat(new String(f.body(), StandardCharsets.UTF_8)).isEqualTo(original);
    }

    @Test
    @DisplayName("JSON 数组（非对象）：原样放行")
    void passesThroughJsonArray() throws IOException {
        String original = "[1,2,3]";
        Forwarded f = forward(original);
        assertThat(new String(f.body(), StandardCharsets.UTF_8)).isEqualTo(original);
    }

    @Test
    @DisplayName("空请求体：原样放行，不抛异常")
    void passesThroughEmptyBody() throws IOException {
        AtomicReference<byte[]> captured = new AtomicReference<>();
        ClientHttpRequestExecution exec = (request, body) -> {
            captured.set(body);
            return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
        };
        HttpRequest req = new HttpRequest() {
            @Override public HttpMethod getMethod() { return HttpMethod.POST; }
            @Override public URI getURI() { return URI.create("https://example.com/x"); }
            @Override public HttpHeaders getHeaders() { return new HttpHeaders(); }
        };
        interceptor.intercept(req, new byte[0], exec);
        assertThat(captured.get()).isEmpty();
    }
}
