package com.example.interview.config;

import com.example.interview.ai.FallbackChatModel;
import com.example.interview.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.client.RestClient;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI 提供方配置降级行为单测（P0-01 / P0-02 回归）
 *
 * <p>背景：AiConfig 此前在 app.ai.chain 解析不出任何可用提供方时直接抛
 * {@link IllegalStateException}，导致**整个 Spring 上下文启动失败**——不只是 AI 不可用，
 * 登录/注册/健康检查等与 AI 无关的功能一并不可用；在 Render 免费层上的表现是
 * 健康检查持续失败 → 实例反复重启 → 站点永远"冷启动"。
 *
 * <p>本测试锁定修复后的契约：**AI 配置缺失只降级 AI 能力，绝不阻断应用启动**。
 * 使用不可路由地址（127.0.0.1:1）让调用快速失败，避免测试期真实外网请求。
 */
@DisplayName("AI 配置降级行为（不阻断启动）")
class AiConfigFallbackTest {

    private static final String UNROUTABLE = "http://127.0.0.1:1";

    private static AiProviderProperties.Provider provider(String name, String apiKey, String model) {
        AiProviderProperties.Provider p = new AiProviderProperties.Provider();
        p.setName(name);
        p.setBaseUrl(UNROUTABLE);
        p.setApiKey(apiKey);
        p.setModel(model);
        p.setTemperature(0.7);
        return p;
    }

    private static ChatModel build(AiProviderProperties props, String springAiBaseUrl) {
        AiConfig config = new AiConfig();
        return config.fallbackChatModel(
                props,
                RestClient.builder(),
                WebClient.builder(),
                1L,
                2L,
                springAiBaseUrl,
                "sk-placeholder",
                "agnes-2.5-flash");
    }

    @Test
    @DisplayName("降级链全部节点 api-key 为空时：仍返回可用 ChatModel，启动不被阻断")
    void emptyChain_doesNotBreakStartup() {
        AiProviderProperties props = new AiProviderProperties();
        List<AiProviderProperties.Provider> chain = new ArrayList<>();
        chain.add(provider("bai-primary", "", "glm-5.3-flash"));
        chain.add(provider("agnes-fallback", "", "agnes-2.5-flash"));
        props.setChain(chain);

        ChatModel model = build(props, UNROUTABLE);

        // 关键契约：不抛异常、返回非空模型 —— 应用可正常启动，登录等非 AI 功能可用
        assertThat(model).isNotNull();
        assertThat(model).isInstanceOf(FallbackChatModel.class);
        // 该模型会回退到 spring.ai.openai.*（本用例指向不可路由地址）
        assertThat(model.toString()).contains("spring-ai-openai/agnes-2.5-flash");
    }

    @Test
    @DisplayName("降级链无可用节点时：AI 调用失败转为可重试的业务文案，而非 500 内部错误")
    void emptyChain_aiCallFailsAsBusinessException() {
        AiProviderProperties props = new AiProviderProperties();
        props.setChain(new ArrayList<>());

        ChatModel model = build(props, UNROUTABLE);

        assertThatThrownBy(() -> model.call(new Prompt("你好")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("AI 服务");
    }

    @Test
    @DisplayName("降级链存在可用节点时：直接使用该节点，不做 spring.ai 回退")
    void nonEmptyChain_usesConfiguredProvider() {
        AiProviderProperties props = new AiProviderProperties();
        List<AiProviderProperties.Provider> chain = new ArrayList<>();
        chain.add(provider("bai-primary", "sk-real-key", "glm-5.3-flash"));
        props.setChain(chain);

        ChatModel model = build(props, UNROUTABLE);

        assertThat(model).isInstanceOf(FallbackChatModel.class);
        assertThat(model.toString()).contains("bai-primary/glm-5.3-flash");
        assertThat(model.toString()).doesNotContain("spring-ai-openai");
    }

    @Test
    @DisplayName("base-url 规范化：剥离误带的 /v1 与结尾斜杠，空值回退默认地址")
    void normalizeBaseUrl() {
        assertThat(AiConfig.normalizeOpenAiBaseUrl("https://apihub.agnes-ai.com/v1"))
                .isEqualTo("https://apihub.agnes-ai.com");
        assertThat(AiConfig.normalizeOpenAiBaseUrl("https://api.siliconflow.cn/v1"))
                .isEqualTo("https://api.siliconflow.cn");
        assertThat(AiConfig.normalizeOpenAiBaseUrl("https://apihub.agnes-ai.com/"))
                .isEqualTo("https://apihub.agnes-ai.com");
        assertThat(AiConfig.normalizeOpenAiBaseUrl("  https://apihub.agnes-ai.com  "))
                .isEqualTo("https://apihub.agnes-ai.com");
        assertThat(AiConfig.normalizeOpenAiBaseUrl(null))
                .isEqualTo("https://apihub.agnes-ai.com");
        assertThat(AiConfig.normalizeOpenAiBaseUrl(""))
                .isEqualTo("https://apihub.agnes-ai.com");
    }

    @Test
    @DisplayName("needsV1Stripping：仅非 v1 版本段厂商需要路径重写")
    void needsV1Stripping_detectsNonV1VersionSegment() {
        // 智谱：版本段是 v4，Spring AI 会多拼 /v1 → 必须重写
        assertThat(AiConfig.needsV1Stripping("https://open.bigmodel.cn/api/paas/v4")).isTrue();
        assertThat(AiConfig.needsV1Stripping("https://open.bigmodel.cn/api/paas/v4/")).isTrue();
        assertThat(AiConfig.needsV1Stripping("https://example.com/api/v2")).isTrue();
        assertThat(AiConfig.needsV1Stripping("https://example.com/v3")).isTrue();
        assertThat(AiConfig.needsV1Stripping("https://example.com/api/paas/v10")).isTrue();

        // 标准 OpenAI 协议：无版本段或恰为 v1 → 原样放行，零开销
        assertThat(AiConfig.needsV1Stripping("https://apihub.agnes-ai.com")).isFalse();
        assertThat(AiConfig.needsV1Stripping("https://api.siliconflow.cn/v1")).isFalse();
        assertThat(AiConfig.needsV1Stripping("https://example.com/v1/")).isFalse();

        // 非版本段结尾（纯字母）不应误判
        assertThat(AiConfig.needsV1Stripping("https://example.com/api")).isFalse();
        assertThat(AiConfig.needsV1Stripping("https://example.com")).isFalse();

        // 空值防御
        assertThat(AiConfig.needsV1Stripping(null)).isFalse();
        assertThat(AiConfig.needsV1Stripping("")).isFalse();
        assertThat(AiConfig.needsV1Stripping("   ")).isFalse();
    }

    @Test
    @DisplayName("toOpenAiCompatibleBaseUrl：保留非 v1 版本段，剥离误带的 /v1")
    void toOpenAiCompatibleBaseUrl_keepsVersionSegment() {
        // 智谱版本段 v4 必须保留（由拦截器负责去掉多余的那层 /v1）
        assertThat(AiConfig.toOpenAiCompatibleBaseUrl("https://open.bigmodel.cn/api/paas/v4"))
                .isEqualTo("https://open.bigmodel.cn/api/paas/v4");
        // 标准厂商：误带的 /v1 仍按原逻辑剥离
        assertThat(AiConfig.toOpenAiCompatibleBaseUrl("https://apihub.agnes-ai.com/v1"))
                .isEqualTo("https://apihub.agnes-ai.com");
    }

    @Test
    @DisplayName("thinking-disabled 松绑定：kebab-case 能绑到 thinkingDisabled（P1-02）")
    void thinkingDisabledBinds() {
        // 这是最容易写错的地方：YAML 里写 thinking-disabled，字段名是 thinkingDisabled，
        // 绑不上时不会报错、只会静默为 null —— 于是「配了关思考」实际没生效。
        Map<String, Object> cfg = Map.of(
                "app.ai.chain[0].name", "zhipu-quality",
                "app.ai.chain[0].base-url", "https://open.bigmodel.cn/api/paas/v4",
                "app.ai.chain[0].api-key", "sk-x",
                "app.ai.chain[0].model", "glm-4.7-flash",
                "app.ai.chain[0].thinking-disabled", "true",
                "app.ai.chain[1].name", "agnes-primary",
                "app.ai.chain[1].base-url", "https://apihub.agnes-ai.com",
                "app.ai.chain[1].api-key", "sk-y",
                "app.ai.chain[1].model", "agnes-2.5-flash");

        AiProviderProperties props = new Binder(new MapConfigurationPropertySource(cfg))
                .bind("app.ai", Bindable.of(AiProviderProperties.class))
                .get();

        assertThat(props.getChain()).hasSize(2);
        assertThat(props.getChain().get(0).getThinkingDisabled()).isTrue();
        // 未声明的节点保持 null —— 不下发该字段，不改变其他厂商的请求体
        assertThat(props.getChain().get(1).getThinkingDisabled()).isNull();
    }

    @Test
    @DisplayName("节点声明 thinking-disabled 时降级链仍能正常构建（不影响启动）")
    void chainWithThinkingDisabledStillBuilds() {
        AiProviderProperties props = new AiProviderProperties();
        AiProviderProperties.Provider zhipu = provider("zhipu-quality", "sk-x", "glm-4.7-flash");
        zhipu.setThinkingDisabled(true);
        props.getChain().add(zhipu);

        ChatModel model = build(props, UNROUTABLE);

        assertThat(model).isInstanceOf(FallbackChatModel.class);
    }
}
