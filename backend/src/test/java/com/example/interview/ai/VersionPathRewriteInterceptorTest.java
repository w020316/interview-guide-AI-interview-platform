package com.example.interview.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 非 v1 版本段路径重写拦截器测试
 *
 * 回归背景（2026-09-20 免费模型接入实测）：Spring AI 1.0.0 的 OpenAiApi 硬编码拼接
 * {@code /v1/chat/completions}，导致智谱（版本段为 {@code /v4}）被拼成
 * {@code /api/paas/v4/v1/chat/completions} → 404，主节点必然失败、每次调用都白撞一次墙。
 */
class VersionPathRewriteInterceptorTest {

    private final VersionPathRewriteInterceptor interceptor = new VersionPathRewriteInterceptor();

    /** 捕获拦截器实际转发出去的 URI */
    private String forwardedUri(String originalUrl) throws IOException {
        AtomicReference<URI> captured = new AtomicReference<>();
        ClientHttpRequestExecution exec = (request, body) -> {
            captured.set(request.getURI());
            return new MockClientHttpResponse(new byte[0], HttpStatus.OK);
        };
        HttpRequest req = new HttpRequest() {
            @Override public HttpMethod getMethod() { return HttpMethod.POST; }
            @Override public URI getURI() { return URI.create(originalUrl); }
            @Override public org.springframework.http.HttpHeaders getHeaders() {
                return new org.springframework.http.HttpHeaders();
            }
        };
        interceptor.intercept(req, new byte[0], exec);
        return captured.get().toString();
    }

    @Test
    @DisplayName("智谱：/v4/v1/chat/completions 被重写为 /v4/chat/completions")
    void rewritesZhipuChatPath() throws IOException {
        assertThat(forwardedUri("https://open.bigmodel.cn/api/paas/v4/v1/chat/completions"))
                .isEqualTo("https://open.bigmodel.cn/api/paas/v4/chat/completions");
    }

    @Test
    @DisplayName("智谱：/v4/v1/embeddings 同样被重写")
    void rewritesZhipuEmbeddingsPath() throws IOException {
        assertThat(forwardedUri("https://open.bigmodel.cn/api/paas/v4/v1/embeddings"))
                .isEqualTo("https://open.bigmodel.cn/api/paas/v4/embeddings");
    }

    @Test
    @DisplayName("标准 OpenAI 厂商（/v1）路径原样放行，不被误改")
    void leavesStandardV1Untouched() throws IOException {
        assertThat(forwardedUri("https://apihub.agnes-ai.com/v1/chat/completions"))
                .isEqualTo("https://apihub.agnes-ai.com/v1/chat/completions");
        assertThat(forwardedUri("https://api.openai.com/v1/chat/completions"))
                .isEqualTo("https://api.openai.com/v1/chat/completions");
    }

    @Test
    @DisplayName("无版本段的路径原样放行")
    void leavesPathWithoutVersionUntouched() throws IOException {
        assertThat(forwardedUri("https://example.com/chat/completions"))
                .isEqualTo("https://example.com/chat/completions");
    }

    @Test
    @DisplayName("重复 v1（/v1/v1/...）也自动修正为单层 v1")
    void collapsesDuplicatedV1() throws IOException {
        assertThat(forwardedUri("https://example.com/v1/v1/chat/completions"))
                .isEqualTo("https://example.com/v1/chat/completions");
    }

    @Test
    @DisplayName("query 参数在重写后保留")
    void preservesQueryString() throws IOException {
        assertThat(forwardedUri("https://open.bigmodel.cn/api/paas/v4/v1/chat/completions?x=1&y=2"))
                .isEqualTo("https://open.bigmodel.cn/api/paas/v4/chat/completions?x=1&y=2");
    }

    @Test
    @DisplayName("响应体与状态码不受重写影响")
    void responsePassesThrough() throws IOException {
        String payload = "{\"ok\":true}";
        ClientHttpRequestExecution exec = (request, body) ->
                new MockClientHttpResponse(payload.getBytes(), HttpStatus.OK);
        HttpRequest req = new HttpRequest() {
            @Override public HttpMethod getMethod() { return HttpMethod.POST; }
            @Override public URI getURI() {
                return URI.create("https://open.bigmodel.cn/api/paas/v4/v1/chat/completions");
            }
            @Override public org.springframework.http.HttpHeaders getHeaders() {
                return new org.springframework.http.HttpHeaders();
            }
        };
        ClientHttpResponse resp = interceptor.intercept(req, new byte[0], exec);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(new String(resp.getBody().readAllBytes())).isEqualTo(payload);
    }
}
