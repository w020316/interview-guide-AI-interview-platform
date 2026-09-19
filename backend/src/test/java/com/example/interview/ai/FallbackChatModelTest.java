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
}
