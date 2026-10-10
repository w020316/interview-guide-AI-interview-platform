package com.example.interview.util;

import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * AI 客户端（RestClient）传输层超时的统一出口（v1.66.1 · R10-F01）。
 *
 * <p><b>为什么必须显式设超时</b>：{@code OpenAiApi} / {@code OpenAiChatModel} 在**不注入**
 * {@code restClientBuilder} 时会退回 Spring AI 默认的 RestClient —— 该默认**不设任何连接/读超时**。
 *
 * <p>平台降级链（{@code AiConfig}）一直显式设了 10s/60s，但「**用户自持 AI Key**」这条链路的
 * {@code baseUrl} 是**运行期由用户填写的任意公网地址**：一旦指向「能建连但永不响应」的地址，
 * 调用会**无限期挂起**。而所有 AI 调用都在 {@code AiConcurrencyGuard} 的**全局许可（仅 5 个）**内执行，
 * 因此**单个用户即可用 5 个挂起请求把全站 AI 功能打满**（其余用户拿到的是「排队超时」的快速失败）。
 * 这与 {@code ResumeImageOcrService} 曾因「无超时占用 AI 许可」被修（B-02）是**同一类缺陷**。
 *
 * <p><b>超时值可用系统属性覆盖</b>，便于测试用极短超时验证「快速失败」：
 * {@code -Dapp.ai.self-key.connect-timeout-seconds=1 -Dapp.ai.self-key.read-timeout-seconds=1}。
 * （每次调用读取而非静态缓存，故测试可在运行期注入。）
 */
public final class AiRestClients {

    /** 默认连接超时（秒） */
    public static final long DEFAULT_CONNECT_TIMEOUT_SECONDS = 10L;

    /** 默认读超时（秒）。远大于实测的 AI 单次耗时，只兜底真正的挂起。 */
    public static final long DEFAULT_READ_TIMEOUT_SECONDS = 60L;

    private static final String CONNECT_TIMEOUT_PROPERTY = "app.ai.self-key.connect-timeout-seconds";
    private static final String READ_TIMEOUT_PROPERTY = "app.ai.self-key.read-timeout-seconds";

    private AiRestClients() {
    }

    /** 连接超时（秒）：系统属性优先，非法/缺失时取默认值。 */
    public static long connectTimeoutSeconds() {
        return positiveOr(Long.getLong(CONNECT_TIMEOUT_PROPERTY), DEFAULT_CONNECT_TIMEOUT_SECONDS);
    }

    /** 读超时（秒）：系统属性优先，非法/缺失时取默认值。 */
    public static long readTimeoutSeconds() {
        return positiveOr(Long.getLong(READ_TIMEOUT_PROPERTY), DEFAULT_READ_TIMEOUT_SECONDS);
    }

    /** 带默认超时的 {@link RestClient.Builder}，供 {@code OpenAiApi.builder().restClientBuilder(...)} 使用。 */
    public static RestClient.Builder withTimeouts() {
        return withTimeouts(Duration.ofSeconds(connectTimeoutSeconds()),
                Duration.ofSeconds(readTimeoutSeconds()));
    }

    /** 带指定超时的 {@link RestClient.Builder}。 */
    public static RestClient.Builder withTimeouts(Duration connectTimeout, Duration readTimeout) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(connectTimeout)
                .withReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(ClientHttpRequestFactories.get(settings));
    }

    private static long positiveOr(Long value, long fallback) {
        return value != null && value > 0 ? value : fallback;
    }
}
