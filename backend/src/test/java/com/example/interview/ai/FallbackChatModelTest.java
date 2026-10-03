package com.example.interview.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多模型降级链 ChatModel 单测
 *
 * <p>覆盖：主模型成功不降级、主模型失败自动降级、全链失败抛 BusinessException、
 * 流式调用（成功/降级/中途失败不降级）、默认选项透传与 toString。
 */
@DisplayName("多模型降级链测试")
class FallbackChatModelTest {

    private final ChatModel primary = mock(ChatModel.class);
    private final ChatModel secondary = mock(ChatModel.class);

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new org.springframework.ai.chat.messages.AssistantMessage(text))));
    }

    @Test
    @DisplayName("call: 空降级链直接拒绝构造")
    void emptyChain_rejected() {
        assertThatThrownBy(() -> new FallbackChatModel(List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("降级链不能为空");
    }

    @Test
    @DisplayName("call: 主模型成功不触发降级")
    void call_primarySuccess() {
        when(primary.call(any(Prompt.class))).thenReturn(response("主模型回答"));
        FallbackChatModel model = new FallbackChatModel(List.of(primary, secondary), List.of("主", "备"));

        assertThat(model.call(new Prompt("问")).getResult().getOutput().getText()).isEqualTo("主模型回答");
        verify(secondary, times(0)).call(any(Prompt.class));
    }

    @Test
    @DisplayName("call: 主模型失败自动降级到备模型")
    void call_primaryFails_fallsBack() {
        when(primary.call(any(Prompt.class))).thenThrow(new RuntimeException("限流 429"));
        when(secondary.call(any(Prompt.class))).thenReturn(response("备模型回答"));
        FallbackChatModel model = new FallbackChatModel(List.of(primary, secondary), List.of("主", "备"));

        assertThat(model.call(new Prompt("问")).getResult().getOutput().getText()).isEqualTo("备模型回答");
        verify(secondary, times(1)).call(any(Prompt.class));
    }

    @Test
    @DisplayName("call: 全链失败抛 BusinessException（U1 业务语义）")
    void call_allFail_businessException() {
        when(primary.call(any(Prompt.class))).thenThrow(new RuntimeException("超时"));
        when(secondary.call(any(Prompt.class))).thenThrow(new RuntimeException("网关 502"));
        FallbackChatModel model = new FallbackChatModel(List.of(primary, secondary), List.of("主", "备"));

        assertThatThrownBy(() -> model.call(new Prompt("问")))
                .isInstanceOf(com.example.interview.common.BusinessException.class)
                .hasMessageContaining("AI 服务暂时不可用")
                .hasRootCauseInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("stream: 主模型流式成功不降级")
    void stream_primarySuccess() {
        when(primary.stream(any(Prompt.class))).thenReturn(Flux.just(response("流式A"), response("流式B")));
        FallbackChatModel model = new FallbackChatModel(List.of(primary, secondary), List.of("主", "备"));

        assertThat(model.stream(new Prompt("问")).collectList().block(Duration.ofSeconds(5))).hasSize(2);
        verify(secondary, times(0)).stream(any(Prompt.class));
    }

    @Test
    @DisplayName("stream: 主模型流式失败（未发出 token）自动降级")
    void stream_primaryFails_fallsBack() {
        when(primary.stream(any(Prompt.class))).thenReturn(Flux.error(new RuntimeException("主模型挂了")));
        when(secondary.stream(any(Prompt.class))).thenReturn(Flux.just(response("备流式")));
        FallbackChatModel model = new FallbackChatModel(List.of(primary, secondary), List.of("主", "备"));

        assertThat(model.stream(new Prompt("问")).collectList().block(Duration.ofSeconds(5))).hasSize(1);
        verify(secondary, times(1)).stream(any(Prompt.class));
    }

    @Test
    @DisplayName("stream: 主模型发出 token 后中途失败，不降级（P2 修复）")
    void stream_midStreamFailure_noFallback() {
        when(primary.stream(any(Prompt.class)))
                .thenReturn(Flux.just(response("前半段")).concatWith(Flux.error(new RuntimeException("中途断流"))));
        FallbackChatModel model = new FallbackChatModel(List.of(primary, secondary), List.of("主", "备"));

        assertThatThrownBy(() -> model.stream(new Prompt("问")).collectList().block(Duration.ofSeconds(5)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("中途断流");
        verify(secondary, times(0)).stream(any(Prompt.class));
    }

    @Test
    @DisplayName("stream: 全链流式失败向上传播错误")
    void stream_allFail_propagatesError() {
        when(primary.stream(any(Prompt.class))).thenReturn(Flux.error(new RuntimeException("主挂")));
        when(secondary.stream(any(Prompt.class))).thenReturn(Flux.error(new RuntimeException("备挂")));
        FallbackChatModel model = new FallbackChatModel(List.of(primary, secondary), List.of("主", "备"));

        assertThatThrownBy(() -> model.stream(new Prompt("问")).collectList().block(Duration.ofSeconds(5)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("getDefaultOptions: 透传主模型默认选项；toString 含模型名单")
    void defaultOptions_andToString() {
        ChatOptions options = ChatOptions.builder().temperature(0.7).build();
        when(primary.getDefaultOptions()).thenReturn(options);
        FallbackChatModel model = new FallbackChatModel(List.of(primary, secondary), List.of("gpt-x", "glm-y"));

        assertThat(model.getDefaultOptions()).isSameAs(options);
        assertThat(model.toString()).isEqualTo("FallbackChatModel[gpt-x, glm-y]");
    }

    // ─────────── 失败诊断（2026-09-19 真机验证：上游错误被吞成笼统 503）───────────

    @Test
    @DisplayName("describeFailure: 从多层包装中挖出上游错误原文（HTTP 200 + error 体的典型形态）")
    void describeFailure_extractsUpstreamError() {
        // 真实形态：上游以 200 返回 {"error":{"message":"Invalid token"}}，
        // Spring AI 反序列化失败，把原文埋成 Jackon 的 cause 消息
        Throwable root = new RuntimeException(
                "Cannot deserialize value of type `OpenAiApi$ChatCompletion` "
                        + "from Object value (token `JsonToken.START_OBJECT`); "
                        + "content: {\"error\":{\"message\":\"Invalid token (request id: abc)\"}");
        Throwable mid = new RuntimeException(
                "Error while extracting response for type [OpenAiApi$ChatCompletion] "
                        + "and content type [application/json;charset=utf-8]", root);

        String detail = FallbackChatModel.describeFailure(mid);

        // 关键：必须命中含上游错误关键字的那一层，而不是外层的无信息量包装
        assertThat(detail).contains("Invalid token");
    }

    @Test
    @DisplayName("describeFailure: 额度耗尽 / 模型名错误同样可辨认")
    void describeFailure_recognizesQuotaAndModelErrors() {
        assertThat(FallbackChatModel.describeFailure(
                new RuntimeException("wrapped", new IllegalStateException(
                        "{\"error\":{\"message\":\"insufficient balance\"}} (quota exceeded)"))))
                .contains("insufficient balance");

        assertThat(FallbackChatModel.describeFailure(
                new RuntimeException("wrapped", new IllegalStateException(
                        "The model `glm-9.9-turbo` does not exist"))))
                .contains("model");
    }

    @Test
    @DisplayName("describeFailure: 无 cause 链时回退到自身消息；空消息回退到类名")
    void describeFailure_fallbacks() {
        assertThat(FallbackChatModel.describeFailure(new RuntimeException("连接被拒绝")))
                .isEqualTo("连接被拒绝");
        assertThat(FallbackChatModel.describeFailure(new RuntimeException())).isNotEmpty();
    }

    @Test
    @DisplayName("describeFailure: 超长消息被截断，避免日志被大段 JSON 淹没")
    void describeFailure_truncatesLongMessage() {
        String huge = "x".repeat(3000);
        String detail = FallbackChatModel.describeFailure(new RuntimeException(huge));
        assertThat(detail.length()).isLessThanOrEqualTo(504);
        assertThat(detail).endsWith("...");
    }

    @Test
    @DisplayName("call: 全链失败时抛 BusinessException（503 语义），且不因诊断逻辑改变行为")
    void call_allFail_throwsBusinessException() {
        when(primary.call(any(Prompt.class)))
                .thenThrow(new RuntimeException("Error while extracting response",
                        new IllegalStateException("{\"error\":{\"message\":\"Invalid token\"}}")));
        when(secondary.call(any(Prompt.class))).thenThrow(new RuntimeException("备挂"));
        FallbackChatModel model = new FallbackChatModel(List.of(primary, secondary), List.of("主", "备"));

        assertThatThrownBy(() -> model.call(new Prompt("问")))
                .isInstanceOf(com.example.interview.common.BusinessException.class)
                .hasMessageContaining("AI 服务暂时不可用");
    }

    // ────────────── 空正文防护（2026-09-20 免费模型实测发现）──────────────
    // 背景：智谱 glm-4.7-flash / glm-4.5-flash 默认开启思考模式，reasoning_content
    // 吃光 max_tokens 后 content 返回空字符串。HTTP 200 + usage 正常，Spring AI 不抛异常。
    // 若照原样返回，用户会拿到「成功状态下的空白答案」——比报错更糟。

    @Test
    @DisplayName("call: 主模型返回空正文时应降级，而不是把空答案当成功返回")
    void call_emptyContent_degradesToNextModel() {
        when(primary.call(any(Prompt.class))).thenReturn(response(""));       // 假成功：空正文
        when(secondary.call(any(Prompt.class))).thenReturn(response("正常答案"));
        FallbackChatModel model = new FallbackChatModel(List.of(primary, secondary), List.of("主", "备"));

        ChatResponse resp = model.call(new Prompt("问"));

        assertThat(resp.getResult().getOutput().getText()).isEqualTo("正常答案");
        verify(secondary, times(1)).call(any(Prompt.class));
    }

    @Test
    @DisplayName("call: 仅空白字符的正文同样判为不可用")
    void call_blankContent_degrades() {
        when(primary.call(any(Prompt.class))).thenReturn(response("   \n\t "));
        when(secondary.call(any(Prompt.class))).thenReturn(response("兜底答案"));
        FallbackChatModel model = new FallbackChatModel(List.of(primary, secondary), List.of("主", "备"));

        assertThat(model.call(new Prompt("问")).getResult().getOutput().getText())
                .isEqualTo("兜底答案");
    }

    @Test
    @DisplayName("call: 全链都返回空正文时抛 BusinessException，绝不返回空答案")
    void call_allEmptyContent_throwsBusinessException() {
        when(primary.call(any(Prompt.class))).thenReturn(response(""));
        when(secondary.call(any(Prompt.class))).thenReturn(response(""));
        FallbackChatModel model = new FallbackChatModel(List.of(primary, secondary), List.of("主", "备"));

        assertThatThrownBy(() -> model.call(new Prompt("问")))
                .isInstanceOf(com.example.interview.common.BusinessException.class)
                .hasMessageContaining("AI 服务暂时不可用");
    }

    @Test
    @DisplayName("hasUsableContent: null 结构、null 文本、空串、空白一律判不可用")
    void hasUsableContent_rejectsEmptyStructures() {
        assertThat(FallbackChatModel.hasUsableContent(null)).isFalse();
        assertThat(FallbackChatModel.hasUsableContent(response(""))).isFalse();
        assertThat(FallbackChatModel.hasUsableContent(response("  "))).isFalse();
        assertThat(FallbackChatModel.hasUsableContent(response("有内容"))).isTrue();
    }

    // ────── 流式路径空正文防护（v1.34.1 P1 修复回归）──────
    // 背景：空正文判定此前只加在 call 路径，stream 路径漏改——上游「思考模式耗尽 max_tokens」
    // 返回 HTTP 200 + 空正文时，用户会看到「成功状态下的空白回答」且无任何错误提示。
    // 两条路径的判据现统一为 hasUsableContent。

    @Test
    @DisplayName("stream: 主模型流为空正文时应降级，而不是把空白回答当成功返回（P1 回归）")
    void stream_primaryEmptyContent_degradesToNextModel() {
        when(primary.stream(any(Prompt.class))).thenReturn(Flux.just(response("")));
        when(secondary.stream(any(Prompt.class))).thenReturn(Flux.just(response("备流式正文")));
        FallbackChatModel model = new FallbackChatModel(List.of(primary, secondary), List.of("主", "备"));

        List<ChatResponse> out = model.stream(new Prompt("问")).collectList().block(Duration.ofSeconds(5));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getResult().getOutput().getText()).isEqualTo("备流式正文");
        verify(secondary, times(1)).stream(any(Prompt.class));
    }

    @Test
    @DisplayName("stream: 纯空白正文同样触发降级")
    void stream_blankContent_degrades() {
        when(primary.stream(any(Prompt.class))).thenReturn(Flux.just(response("   \n\t ")));
        when(secondary.stream(any(Prompt.class))).thenReturn(Flux.just(response("兜底")));
        FallbackChatModel model = new FallbackChatModel(List.of(primary, secondary), List.of("主", "备"));

        List<ChatResponse> out = model.stream(new Prompt("问")).collectList().block(Duration.ofSeconds(5));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getResult().getOutput().getText()).isEqualTo("兜底");
    }

    @Test
    @DisplayName("stream: 全链都返回空正文时抛 BusinessException（503 语义），不返回空白")
    void stream_allEmptyContent_throwsBusinessException() {
        when(primary.stream(any(Prompt.class))).thenReturn(Flux.just(response("")));
        when(secondary.stream(any(Prompt.class))).thenReturn(Flux.just(response("")));
        FallbackChatModel model = new FallbackChatModel(List.of(primary, secondary), List.of("主", "备"));

        assertThatThrownBy(() -> model.stream(new Prompt("问")).collectList().block(Duration.ofSeconds(5)))
                .isInstanceOf(com.example.interview.common.BusinessException.class)
                .hasMessageContaining("AI 服务暂时不可用");
    }

    @Test
    @DisplayName("stream: 空正文 chunk 被过滤，下游只收到有内容的 token")
    void stream_emptyChunksFilteredOut() {
        when(primary.stream(any(Prompt.class))).thenReturn(Flux.just(
                response(""), response("  "), response("第一个字"), response("后续内容")));
        FallbackChatModel model = new FallbackChatModel(List.of(primary, secondary), List.of("主", "备"));

        List<ChatResponse> out = model.stream(new Prompt("问")).collectList().block(Duration.ofSeconds(5));

        assertThat(out).hasSize(2);
        assertThat(out.get(0).getResult().getOutput().getText()).isEqualTo("第一个字");
        assertThat(out.get(1).getResult().getOutput().getText()).isEqualTo("后续内容");
        verify(secondary, times(0)).stream(any(Prompt.class));
    }

    @Test
    @DisplayName("stream: 首节点空正文→降级中途失败时仍向上传播错误（不吞异常）")
    void stream_emptyThenFailure_propagatesError() {
        when(primary.stream(any(Prompt.class))).thenReturn(Flux.just(response("")));
        when(secondary.stream(any(Prompt.class)))
                .thenReturn(Flux.error(new RuntimeException("备节点不可用")));
        FallbackChatModel model = new FallbackChatModel(List.of(primary, secondary), List.of("主", "备"));

        assertThatThrownBy(() -> model.stream(new Prompt("问")).collectList().block(Duration.ofSeconds(5)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("备节点不可用");
    }

    // ══════════ v1.48.0（第六轮 P1）：按厂商 RPM 令牌桶 + 错误码语义 ══════════

    /** 记录退避等待时长（免真实等待），用于断言「退避后重试本节点」 */
    private FallbackChatModel withPolicy(List<ChatModel> delegates, List<String> names,
                                         List<String> keys, AiRateLimiter limiter,
                                         List<Long> sleeps, int maxRetries) {
        return new FallbackChatModel(delegates, names, keys, limiter, sleeps::add, maxRetries);
    }

    @Test
    @DisplayName("classifyFailure: 1305 平台过载 / 1302 与 429 账户限流 / 其它")
    void classifyFailure_kinds() {
        assertThat(FallbackChatModel.classifyFailure(new RuntimeException(
                "{\"error\":{\"code\":\"1305\",\"message\":\"该模型当前访问量过大\"}}")))
                .isEqualTo(FallbackChatModel.FailureKind.PLATFORM_OVERLOAD);
        assertThat(FallbackChatModel.classifyFailure(new RuntimeException(
                "{\"error\":{\"code\":\"1302\",\"message\":\"触发账户速率限制\"}}")))
                .isEqualTo(FallbackChatModel.FailureKind.ACCOUNT_RATE_LIMIT);
        assertThat(FallbackChatModel.classifyFailure(new RuntimeException("HTTP 429 Too Many Requests")))
                .isEqualTo(FallbackChatModel.FailureKind.ACCOUNT_RATE_LIMIT);
        assertThat(FallbackChatModel.classifyFailure(new RuntimeException("You've reached the API rate limit for free users")))
                .isEqualTo(FallbackChatModel.FailureKind.ACCOUNT_RATE_LIMIT);
        assertThat(FallbackChatModel.classifyFailure(new RuntimeException("网关 502")))
                .isEqualTo(FallbackChatModel.FailureKind.OTHER);
    }

    @Test
    @DisplayName("classifyFailure: 1305 优先于同时出现的 429（全局过载应立即换节点）")
    void classifyFailure_overloadTakesPrecedenceOver429() {
        Throwable e = new RuntimeException("HTTP 429", new IllegalStateException(
                "{\"error\":{\"code\":\"1305\",\"message\":\"平台服务过载\"}}"));
        assertThat(FallbackChatModel.classifyFailure(e)).isEqualTo(FallbackChatModel.FailureKind.PLATFORM_OVERLOAD);
    }

    @Test
    @DisplayName("1302 账户限流：退避后重试本节点，重试成功则不再降级")
    void accountRateLimit_1302_retriesSameNodeThenSucceeds() {
        List<Long> sleeps = new java.util.ArrayList<>();
        RuntimeException limited = new RuntimeException("{\"error\":{\"code\":\"1302\",\"message\":\"触发账户速率限制\"}}");
        when(primary.call(any(Prompt.class)))
                .thenThrow(limited).thenThrow(limited).thenReturn(response("重试成功"));
        FallbackChatModel model = withPolicy(List.of(primary, secondary), List.of("智谱", "备"),
                null, null, sleeps, 2);

        assertThat(model.call(new Prompt("问")).getResult().getOutput().getText()).isEqualTo("重试成功");
        verify(primary, times(3)).call(any(Prompt.class));
        verify(secondary, times(0)).call(any(Prompt.class));
        assertThat(sleeps).as("应有 2 次退避（1s / 2s 量级）").hasSize(2);
        assertThat(sleeps.get(0)).isBetween(1000L, 1300L);
        assertThat(sleeps.get(1)).isBetween(2000L, 2600L);
    }

    @Test
    @DisplayName("429 免费档限流：退避重试耗尽后降级到下一节点")
    void accountRateLimit_429_retriesThenDegrades() {
        List<Long> sleeps = new java.util.ArrayList<>();
        when(primary.call(any(Prompt.class))).thenThrow(new RuntimeException("限流 429"));
        when(secondary.call(any(Prompt.class))).thenReturn(response("备模型回答"));
        FallbackChatModel model = withPolicy(List.of(primary, secondary), List.of("Agnes", "备"),
                null, null, sleeps, 2);

        assertThat(model.call(new Prompt("问")).getResult().getOutput().getText()).isEqualTo("备模型回答");
        verify(primary, times(3)).call(any(Prompt.class)); // 1 次原始 + 2 次退避重试
        verify(secondary, times(1)).call(any(Prompt.class));
        assertThat(sleeps).hasSize(2);
    }

    @Test
    @DisplayName("1305 平台过载：立即换下一节点，不重试、不等待")
    void platformOverload_1305_degradesImmediately() {
        List<Long> sleeps = new java.util.ArrayList<>();
        when(primary.call(any(Prompt.class))).thenThrow(new RuntimeException(
                "{\"error\":{\"code\":\"1305\",\"message\":\"该模型当前访问量过大\"}}"));
        when(secondary.call(any(Prompt.class))).thenReturn(response("备模型回答"));
        FallbackChatModel model = withPolicy(List.of(primary, secondary), List.of("智谱", "备"),
                null, null, sleeps, 2);

        assertThat(model.call(new Prompt("问")).getResult().getOutput().getText()).isEqualTo("备模型回答");
        verify(primary, times(1)).call(any(Prompt.class)); // 不重试本节点
        verify(secondary, times(1)).call(any(Prompt.class));
        assertThat(sleeps).as("平台过载不应退避等待").isEmpty();
    }

    @Test
    @DisplayName("本地 RPM 令牌桶触顶：不调用该节点，直接降级")
    void localRpmExhausted_degradesWithoutCalling() {
        // rpm=1：第一次调用消耗掉唯一令牌，第二次必然触顶
        AiRateLimiter limiter = new AiRateLimiter(java.util.Map.of("agnes", 1));
        when(primary.call(any(Prompt.class))).thenReturn(response("首次成功"));
        when(secondary.call(any(Prompt.class))).thenReturn(response("降级成功"));
        FallbackChatModel model = withPolicy(List.of(primary, secondary), List.of("Agnes", "备"),
                List.of("agnes", "nvidia"), limiter, new java.util.ArrayList<>(), 0);

        assertThat(model.call(new Prompt("问")).getResult().getOutput().getText()).isEqualTo("首次成功");
        // 第二次：agnes 桶已空 → 跳过 primary，直接用 secondary
        assertThat(model.call(new Prompt("问")).getResult().getOutput().getText()).isEqualTo("降级成功");
        verify(primary, times(1)).call(any(Prompt.class));
        verify(secondary, times(1)).call(any(Prompt.class));
    }

    @Test
    @DisplayName("反向对照：RPM 不限流（rpm=0）时两次都走主节点，不被跳过")
    void reverseControl_unlimitedRpm_neverSkips() {
        // 与 localRpmExhausted_degradesWithoutCalling 唯一差别：rpm=0（不限流）。
        // 若把限流阈值调成无限，上面那条「触顶降级」测试即失效——本条锁死该对照关系。
        AiRateLimiter limiter = new AiRateLimiter(java.util.Map.of("agnes", 0));
        when(primary.call(any(Prompt.class))).thenReturn(response("主节点回答"));
        FallbackChatModel model = withPolicy(List.of(primary, secondary), List.of("Agnes", "备"),
                List.of("agnes", "nvidia"), limiter, new java.util.ArrayList<>(), 0);

        assertThat(model.call(new Prompt("问")).getResult().getOutput().getText()).isEqualTo("主节点回答");
        assertThat(model.call(new Prompt("问")).getResult().getOutput().getText()).isEqualTo("主节点回答");
        verify(primary, times(2)).call(any(Prompt.class));
        verify(secondary, times(0)).call(any(Prompt.class));
    }

    @Test
    @DisplayName("stream: 本地 RPM 触顶时流式跳过该节点并降级")
    void stream_localRpmExhausted_degrades() {
        AiRateLimiter limiter = new AiRateLimiter(java.util.Map.of("agnes", 1));
        when(primary.stream(any(Prompt.class))).thenReturn(Flux.just(response("主流式")));
        when(secondary.stream(any(Prompt.class))).thenReturn(Flux.just(response("备流式")));
        FallbackChatModel model = withPolicy(List.of(primary, secondary), List.of("Agnes", "备"),
                List.of("agnes", "nvidia"), limiter, new java.util.ArrayList<>(), 0);

        List<ChatResponse> first = model.stream(new Prompt("问")).collectList().block(Duration.ofSeconds(5));
        List<ChatResponse> second = model.stream(new Prompt("问")).collectList().block(Duration.ofSeconds(5));
        assertThat(first).hasSize(1);
        assertThat(first.get(0).getResult().getOutput().getText()).isEqualTo("主流式");
        assertThat(second).hasSize(1);
        assertThat(second.get(0).getResult().getOutput().getText()).isEqualTo("备流式");
        verify(primary, times(1)).stream(any(Prompt.class));
        verify(secondary, times(1)).stream(any(Prompt.class));
    }
}