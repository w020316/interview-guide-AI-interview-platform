package com.example.interview.config;

import com.example.interview.ai.FallbackChatModel;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 请求级接线验证：确认 {@code thinking-disabled: true} 真的会改写**实际发出的请求体**。
 *
 * <h2>为什么必须做请求级验证（第三轮 P1-02）</h2>
 *
 * <p>单元测试只证明了「拦截器拿到 body 会改写它」，证明不了「拦截器被挂上了」。
 * 这个项目**正好踩过这个坑**（{@code AiResponseDiagnosticInterceptor} 的类注释）：
 * 拦截器挂在入参 builder 上、节点用 clone 构造 → 拦截器从未被调用。
 *
 * <p>只测拦截器本身、不测接线，就会出现「测试全绿但功能没生效」——正是 P1-02 这类缺陷
 * 最容易的复发方式。
 *
 * <h2>为什么用本地 HttpServer 而不是 MockRestServiceServer</h2>
 *
 * <p>实测发现：{@code RestClient.Builder.clone()} **保留** {@code requestInterceptor}
 * （见 {@link RestClientBuilderCloneExperiment}），但**不保留 {@code requestFactory}**。
 * 而 {@code MockRestServiceServer} 正是靠替换 requestFactory 生效的，因此在本项目的
 * 构建路径（{@code withDiagnostics(...).clone()}）上会被丢掉，请求会真的打到外网。
 * 改为起一个本地 {@link HttpServer} 直接接住请求，既离线又更接近真实链路。
 */
@DisplayName("AI 链路请求级接线：thinking-disabled 是否真的生效")
class AiChainRequestWiringTest {

    private static final String CHAT_RESPONSE = """
            {"id":"1","object":"chat.completion","created":1,"model":"glm-4.7-flash",
             "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
            """;

    private static HttpServer server;
    private static String baseUrl;
    /** 本地服务要返回的状态码（默认 200；测错误路径时置为 401） */
    private static volatile int responseStatus = 200;
    /** 接到的请求体（每个用例前清空） */
    private static final List<String> received = new ArrayList<>();

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (received) {
                received.add(body);
            }
            int status = responseStatus;
            String payload = status == 200
                    ? CHAT_RESPONSE
                    : "{\"error\":{\"code\":\"401\",\"message\":\"Invalid token\"}}";
            byte[] out = payload.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
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

    private static AiProviderProperties.Provider provider(String model, Boolean thinkingDisabled) {
        AiProviderProperties.Provider p = new AiProviderProperties.Provider();
        p.setName("local-probe");
        p.setBaseUrl(baseUrl);
        p.setApiKey("sk-test");
        p.setModel(model);
        p.setTemperature(0.7);
        p.setThinkingDisabled(thinkingDisabled);
        return p;
    }

    /** 走真实链路发一次请求，返回模型回答（请求体会被本地服务记录） */
    private static String call(AiProviderProperties.Provider p) {
        AiProviderProperties props = new AiProviderProperties();
        props.getChain().add(p);

        AiConfig config = new AiConfig();
        ChatModel model = config.fallbackChatModel(
                props, RestClient.builder(), WebClient.builder(), 1L, 2L,
                "https://apihub.agnes-ai.com", "sk-placeholder", "agnes-2.5-flash");
        assertThat(model).isInstanceOf(FallbackChatModel.class);

        synchronized (received) {
            received.clear();
        }
        return model.call(new Prompt("hi")).getResult().getOutput().getText();
    }

    private static String lastRequestBody() {
        synchronized (received) {
            assertThat(received).as("本地服务应接到 1 个请求").hasSize(1);
            return received.get(0);
        }
    }

    @Test
    @DisplayName("thinking-disabled=true → 实际请求体里带上 thinking:disabled（P1-02 接线锁定）")
    void thinkingDisabledReachesRequestBody() {
        assertThat(call(provider("glm-4.7-flash", true))).isEqualTo("ok");

        String body = lastRequestBody();
        assertThat(body)
                .as("拦截器必须真的挂在链路上，而不只是自己工作正常")
                .contains("\"thinking\":{\"type\":\"disabled\"}");
        assertThat(body).contains("\"model\":\"glm-4.7-flash\"");
    }

    @Test
    @DisplayName("未声明 thinking-disabled → 请求体里不出现 thinking（不影响其他厂商）")
    void noThinkingFieldWhenNotDeclared() {
        assertThat(call(provider("glm-4-flash", null))).isEqualTo("ok");

        assertThat(lastRequestBody()).doesNotContain("thinking");
    }

    @Test
    @DisplayName("thinking-disabled=false 显式关闭 → 同样不下发该字段")
    void explicitFalseDoesNotInject() {
        assertThat(call(provider("glm-4-flash", false))).isEqualTo("ok");

        assertThat(lastRequestBody()).doesNotContain("thinking");
    }

    /**
     * 诊断拦截器是否真的被调用、被调用几次。
     *
     * <p>两个疑问都要回答：
     * <ol>
     *   <li>它的类注释断言「拦截器从未被调用」—— 需要证明现在是否真的挂上了；</li>
     *   <li>{@code AiConfig} 在入参 builder 上挂一次、各节点又经 {@code withDiagnostics} 挂一次，
     *       而实测 {@code clone()} 会保留拦截器 → 节点 builder 上可能有**两份**。</li>
     * </ol>
     *
     * <p>做法：让本地服务返回 401 + 错误体（拦截器遇到错误响应会打日志），
     * 用 Logback 的 ListAppender 数一下日志条数。
     */
    @Test
    @DisplayName("诊断拦截器：确实被调用；且数一数是否被重复挂载（跑了几次）")
    void diagnosticInterceptorInvocationCount() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(com.example.interview.ai.AiResponseDiagnosticInterceptor.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        responseStatus = 401;
        try {
            call(provider("glm-4-flash", null));
        } catch (Exception ignored) {
            // 上游 401 → FallbackChatModel 会转成 BusinessException，这里只关心日志
        } finally {
            responseStatus = 200;
            logger.detachAppender(appender);
        }

        long diagnosticLogs = appender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("AI 上游"))
                .count();

        System.out.println("[诊断拦截器] 上游 401 时记录到的日志条数 = " + diagnosticLogs);
        assertThat(diagnosticLogs)
                .as("拦截器必须恰好被调用 1 次：≥1 证明真的挂上了；==1 证明没有重复挂载")
                .isEqualTo(1);
    }
}
