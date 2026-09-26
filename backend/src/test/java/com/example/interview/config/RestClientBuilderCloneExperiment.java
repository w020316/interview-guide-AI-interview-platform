package com.example.interview.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【框架行为实验】{@code RestClient.Builder.clone()} 到底保不保留 {@code requestInterceptor}？
 *
 * <h2>为什么必须实测</h2>
 *
 * <p>这个项目里有一句**靠注释传递、从未被验证**的断言
 * （见 {@code AiResponseDiagnosticInterceptorTest} 的类注释）：
 *
 * <blockquote>
 * 拦截器最初挂在 fallbackChatModel 的入参 builder 上，而节点用的是 {@code clone()}，
 * 而 {@code clone()} 不继承 {@code requestInterceptors} → 拦截器从未被调用。
 * </blockquote>
 *
 * <p>而 {@code AiConfig} 现在的写法是 {@code withDiagnostics(restClientBuilder).clone()}，
 * 其中 {@code withDiagnostics} = {@code builder.clone().requestInterceptor(DIAGNOSTIC)}。
 * **如果 clone 真的丢弃拦截器，那么第二次 clone 会把刚挂上的诊断拦截器又丢掉**
 * —— 也就是「修了但没生效」。这两种可能性必须用实验区分，不能靠读注释。
 *
 * <p>实验方式：起一个本地 {@link HttpServer}，给 builder 挂一个计数器拦截器，
 * 分别测「直接用」与「clone 之后用」两种情况下拦截器被调用的次数。
 *
 * <p>结论（2026-09-26 实测）：见各用例断言。本类同时是**框架行为的回归防线** ——
 * 将来升级 Spring 若改变 clone 语义，这里会先红。
 */
@DisplayName("框架行为实验：RestClient.Builder.clone() 与拦截器")
class RestClientBuilderCloneExperiment {

    private static HttpServer server;
    private static String url;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/ping";
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** 挂一个计数拦截器，返回调用次数容器 */
    private static AtomicInteger counting(RestClient.Builder builder) {
        AtomicInteger hits = new AtomicInteger();
        builder.requestInterceptor((request, body, execution) -> {
            hits.incrementAndGet();
            return execution.execute(request, body);
        });
        return hits;
    }

    private static void fire(RestClient client) {
        client.get().uri(url).retrieve().toBodilessEntity();
    }

    @Test
    @DisplayName("直接用（不 clone）：拦截器生效")
    void directUse_runsInterceptor() {
        RestClient.Builder builder = RestClient.builder();
        AtomicInteger hits = counting(builder);

        fire(builder.build());

        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ clone() 之后再用：拦截器是否还在？（决定 withDiagnostics(...).clone() 写法是否有效）")
    void cloneThenUse_interceptorSurvives() {
        RestClient.Builder builder = RestClient.builder();
        AtomicInteger hits = counting(builder);

        RestClient.Builder cloned = builder.clone();
        fire(cloned.build());

        // 若这里为 0，说明 AiConfig 的 withDiagnostics(b).clone() 会把刚挂上的
        // 诊断拦截器丢掉 —— 即「修了但没生效」。实测结果见断言。
        assertThat(hits.get())
                .as("clone() 是否保留 requestInterceptor —— 决定 AiConfig 的写法是否有效")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("先挂拦截器再 clone 两次（复刻 withDiagnostics(x).clone() 的形状）")
    void doubleCloneKeepsInterceptor() {
        RestClient.Builder builder = RestClient.builder();
        AtomicInteger hits = new AtomicInteger();
        // 复刻 withDiagnostics 的写法：clone → 挂拦截器 → 再 clone
        RestClient.Builder nodeBuilder = builder.clone()
                .requestInterceptor((request, body, execution) -> {
                    hits.incrementAndGet();
                    return execution.execute(request, body);
                })
                .clone();

        fire(nodeBuilder.build());

        assertThat(hits.get())
                .as("AiConfig 里 withDiagnostics(restClientBuilder).clone() 的实际形状")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("★ clone() 是否保留 requestFactory？（决定 MockRestServiceServer 能不能用在 clone 路径上）")
    void cloneThenUse_requestFactorySurvives() {
        // MockRestServiceServer 靠替换 requestFactory 生效
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(builder).build();
        mockServer.expect(requestTo(url)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        // 从 clone 构建客户端：若 factory 被保留，请求会由 mock 接住（本地服务收到 0 次）；
        // 若被丢弃，请求会真的打到本地 HttpServer（mock 的期望无法被消费）。
        fire(builder.clone().build());

        try {
            mockServer.verify();
        } catch (AssertionError e) {
            // 走到这里说明 factory 在 clone 时丢失 → 请求打到本地服务
            throw new AssertionError(
                    "clone() 未保留 requestFactory：MockRestServiceServer 无法用于本项目"
                            + "（withDiagnostics(...).clone()）的构建路径。"
                            + "因此接线测试应改用本地 HttpServer 接请求。原始错误：" + e.getMessage());
        }
    }
}
