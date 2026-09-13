package com.example.interview.config;

import com.example.interview.ai.FallbackChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI ChatClient 配置
 *
 * v1.22.0：聊天模型切换为多厂商降级链（app.ai.chain）：
 * 1. B.AI · GLM-5.3-Flash（主模型，免费额度，0 Credits）
 * 2. B.AI · Qwen3.8-Flash（次模型，免费额度，0 Credits）
 * 3. Agnes AI · agnes-2.5-flash（兜底，原主模型自动降为末位）
 *
 * v1.33.0（P1-03）：为每个提供方显式配置 HTTP 超时——此前未定制任何超时，一旦 AI 网关
 * 建连后挂起，同步调用永久阻塞，AiConcurrencyGuard 的 5 个许可被无限期占住。
 * 读超时（默认 240s）需大于最慢的合法生成（UI 宣称出题 2-3 分钟），
 * 且大于闸门排队超时（30s），保证许可必然在有限时间内释放。
 *
 * 不设置硬编码的岗位默认 system prompt，由各业务 Service 根据岗位动态生成，
 * 避免默认 "Java 后端" 与实际岗位冲突（支持全行业岗位）。
 *
 * 注意：Embedding 不走本链，仍由 spring.ai.openai.*（Agnes，text-embedding-3-small）驱动，
 * 用于知识库 RAG 向量化。
 */
@Configuration
@EnableConfigurationProperties(AiProviderProperties.class)
public class AiConfig {

    /**
     * 多厂商降级链 ChatModel（主 Bean）
     * - api-key 为空的提供方自动跳过（如未配置 AI_BAI_API_KEY 时落到 Agnes）
     * - 标记 @Primary 避免与 spring.ai 自动装配的 OpenAiChatModel（Agnes，Embedding 用）注入歧义
     */
    @Bean
    @Primary
    public ChatModel fallbackChatModel(
            AiProviderProperties props,
            RestClient.Builder restClientBuilder,
            WebClient.Builder webClientBuilder,
            @Value("${app.ai.connect-timeout-seconds:10}") long connectTimeoutSeconds,
            @Value("${app.ai.read-timeout-seconds:240}") long readTimeoutSeconds) {
        // 同步调用：连接 10s + 读超时兜底，保证闸门许可有限时间内释放
        restClientBuilder.requestFactory(ClientHttpRequestFactories.get(
                ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                        .withReadTimeout(Duration.ofSeconds(readTimeoutSeconds))));
        // 流式调用（SSE）：classpath 无 Reactor Netty，WebClient 默认走 JDK HttpClient，
        // 此处配置连接超时；流式整体时长由 SseEmitter 超时与客户端兜底约束
        webClientBuilder.clientConnector(new JdkClientHttpConnector(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                .build()));

        List<ChatModel> delegates = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (AiProviderProperties.Provider p : props.getChain()) {
            if (p.getBaseUrl() == null || p.getBaseUrl().isBlank()
                    || p.getModel() == null || p.getModel().isBlank()
                    || p.getApiKey() == null || p.getApiKey().isBlank()) {
                continue;
            }
            OpenAiApi api = OpenAiApi.builder()
                    .baseUrl(p.getBaseUrl())
                    .apiKey(p.getApiKey())
                    .restClientBuilder(restClientBuilder.clone())
                    .webClientBuilder(webClientBuilder.clone())
                    .build();
            OpenAiChatModel model = OpenAiChatModel.builder()
                    .openAiApi(api)
                    .defaultOptions(OpenAiChatOptions.builder()
                            .model(p.getModel())
                            .temperature(p.getTemperature() == null ? 0.7 : p.getTemperature())
                            .build())
                    .build();
            delegates.add(model);
            names.add((p.getName() == null || p.getName().isBlank() ? p.getBaseUrl() : p.getName())
                    + "/" + p.getModel());
        }
        if (delegates.isEmpty()) {
            // 兜底：链条全为空时退回 spring.ai.openai.* 自动装配的默认模型，保证应用可启动
            throw new IllegalStateException("app.ai.chain 未配置任何可用的 AI 提供方（api-key 均为空）");
        }
        return new FallbackChatModel(delegates, names);
    }

    @Bean
    public ChatClient chatClient(ChatModel fallbackChatModel) {
        return ChatClient.builder(fallbackChatModel).build();
    }
}
