package com.example.interview.util;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 自持 Key 传输层超时的回归守卫（R10-F01）。
 *
 * <p><b>为什么这个测试重要</b>：平台降级链（{@code AiConfig}）一直显式设了 10s/60s 超时，
 * 但「用户自持 AI Key」的 {@code baseUrl} 是**运行期由用户填写的任意公网地址**。
 * 不注入 {@code restClientBuilder} 时 Spring AI 退回的默认客户端**不设任何超时** →
 * 指向「能建连但永不响应」的地址会让调用无限期挂起，而所有 AI 调用都在
 * {@code AiConcurrencyGuard} 的**全局许可（仅 5 个）**内执行 → **单个用户即可打满全站 AI 功能**。
 *
 * <p>本测试用 JDK 自带 {@code com.sun.net.httpserver} 起一个「接受连接但永不响应」的真服务端
 * （零新增依赖，与项目「测 HTTP 层行为要用真服务端」的既有范式一致），
 * 断言客户端在超时后**快速失败**而不是挂到黑洞服务端的 20s。
 */
@DisplayName("AiRestClients 自持 Key 传输层超时（R10-F01）")
class AiRestClientsTest {

    private static final String CONNECT_PROP = "app.ai.self-key.connect-timeout-seconds";
    private static final String READ_PROP = "app.ai.self-key.read-timeout-seconds";

    private static HttpServer blackhole;
    private static String blackholeBaseUrl;

    @BeforeAll
    static void startBlackhole() throws IOException {
        blackhole = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 故意不写任何响应：连接建立后一直挂着
        blackhole.createContext("/", exchange -> {
            try {
                Thread.sleep(20_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        // 必须用 daemon 线程：否则 handler 线程会阻止 JVM 退出（Maven 卡住）
        blackhole.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "blackhole-handler");
            t.setDaemon(true);
            return t;
        }));
        blackhole.start();
        blackholeBaseUrl = "http://127.0.0.1:" + blackhole.getAddress().getPort();
    }

    @AfterAll
    static void stopBlackhole() {
        if (blackhole != null) {
            blackhole.stop(0);
        }
    }

    @Test
    @DisplayName("默认超时：连接 10s / 读 60s（与平台链 AiConfig 同口径）")
    void defaults() {
        System.clearProperty(CONNECT_PROP);
        System.clearProperty(READ_PROP);

        assertThat(AiRestClients.connectTimeoutSeconds()).isEqualTo(10L);
        assertThat(AiRestClients.readTimeoutSeconds()).isEqualTo(60L);
    }

    @Test
    @DisplayName("系统属性可覆盖，非法值回落默认（测试需要极短超时才可验证快速失败）")
    void overridable() {
        System.setProperty(CONNECT_PROP, "3");
        System.setProperty(READ_PROP, "0");     // 非法（<=0）→ 回落默认 60
        try {
            assertThat(AiRestClients.connectTimeoutSeconds()).isEqualTo(3L);
            assertThat(AiRestClients.readTimeoutSeconds()).isEqualTo(60L);
        } finally {
            System.clearProperty(CONNECT_PROP);
            System.clearProperty(READ_PROP);
        }
    }

    @Test
    @DisplayName("读超时确实生效：指向黑洞地址在 1s 读超时后失败，而不是挂到 20s")
    void blackholeFailsFast() {
        System.setProperty(CONNECT_PROP, "1");
        System.setProperty(READ_PROP, "1");
        try {
            var client = AiRestClients.withTimeouts().build();

            long t0 = System.currentTimeMillis();
            assertThatThrownBy(() -> client.get().uri(blackholeBaseUrl + "/v1/models")
                    .retrieve().body(String.class))
                    .isInstanceOf(Exception.class);
            long elapsed = System.currentTimeMillis() - t0;

            assertThat(elapsed)
                    .as("读超时 1s 后应失败；若超时未生效会一直等到黑洞的 20s")
                    .isLessThan(10_000L);
        } finally {
            System.clearProperty(CONNECT_PROP);
            System.clearProperty(READ_PROP);
        }
    }

    @Test
    @DisplayName("静态守卫：两处自持 Key 客户端构造都必须注入带超时的 restClientBuilder")
    void bothSelfKeySitesInjectTimeoutBuilder() throws IOException {
        // 为什么用源码守卫：超时是「注入了才生效」的配置。若日后有人新写一处 OpenAiApi.builder()
        // 而忘了 restClientBuilder，编译与常规单测都不会报错，线上却会在上游挂起时耗尽全局 AI 许可。
        Path src = Path.of(System.getProperty("user.dir"), "src", "main", "java", "com", "example", "interview");
        Path routing = src.resolve("ai").resolve("SelfKeyAwareChatModel.java");
        Path keyService = src.resolve("service").resolve("UserAiKeyService.java");
        assertThat(routing).as("源码路径应存在（user.dir=%s）", System.getProperty("user.dir")).exists();
        assertThat(keyService).exists();

        String expected = "restClientBuilder(com.example.interview.util.AiRestClients.withTimeouts())";
        assertThat(squeeze(routing)).contains(expected);
        assertThat(squeeze(keyService)).contains(expected);
    }

    private static String squeeze(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8).replaceAll("\\s+", "");
    }
}
