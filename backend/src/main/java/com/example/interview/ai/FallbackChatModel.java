package com.example.interview.ai;

import com.example.interview.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.ChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 多模型降级链 ChatModel
 *
 * 按 chain 顺序调用；主模型失败（超时/限流/5xx/网络异常）时自动降级到下一个模型，
 * 保证 AI 功能在单厂商故障时仍可用。
 *
 * 同时实现 call()（同步，面试题生成/评估/岗位分析等）与 stream()（SSE 流式问答）。
 */
public class FallbackChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(FallbackChatModel.class);

    private final List<ChatModel> delegates;
    private final List<String> names;

    public FallbackChatModel(List<ChatModel> delegates, List<String> names) {
        if (delegates == null || delegates.isEmpty()) {
            throw new IllegalArgumentException("AI 模型降级链不能为空");
        }
        this.delegates = delegates;
        this.names = names;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Exception last = null;
        for (int i = 0; i < delegates.size(); i++) {
            try {
                ChatResponse resp = delegates.get(i).call(prompt);
                if (i > 0) {
                    log.warn("已降级到第 {} 顺位模型 {}", i + 1, names.get(i));
                }
                return resp;
            } catch (Exception e) {
                log.warn("AI 模型 {} 调用失败，尝试降级：{}", names.get(i), e.getMessage());
                last = e;
            }
        }
        // U1：降级链全失败属于"AI 服务暂不可用"的可重试业务故障，以 BusinessException 承载
        // 明确文案（全局处理器映射 503），替代此前语义混淆的 IllegalStateException→500"服务器内部错误"
        throw new BusinessException("AI 服务暂时不可用，请稍后重试", last);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            // v1.23.1 修复（P2）：仅当尚未向下游发出任何 token 时才允许降级。
            // 此前 onErrorResume 作用于整条流，流中途失败也会切换模型重发，
            // 导致前半段旧模型内容与新模型内容拼接错乱
            AtomicBoolean firstTokenSent = new AtomicBoolean(false);
            Flux<ChatResponse> flux = delegates.get(0).stream(prompt)
                    .doOnNext(resp -> firstTokenSent.set(true));
            for (int i = 1; i < delegates.size(); i++) {
                final int idx = i;
                flux = flux.onErrorResume(e -> {
                    if (firstTokenSent.get()) {
                        log.warn("AI 模型 {} 流式输出中途失败（已发出 token，不降级）：{}", names.get(idx - 1), e.getMessage());
                        return Flux.error(e);
                    }
                    log.warn("AI 模型 {} 流式调用失败（未发出 token），尝试降级：{}", names.get(idx - 1), e.getMessage());
                    Flux<ChatResponse> next = Flux.defer(() -> delegates.get(idx).stream(prompt))
                            .doOnNext(resp -> firstTokenSent.set(true));
                    return next;
                });
            }
            return flux;
        });
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegates.get(0).getDefaultOptions();
    }

    @Override
    public String toString() {
        return "FallbackChatModel" + names;
    }
}
