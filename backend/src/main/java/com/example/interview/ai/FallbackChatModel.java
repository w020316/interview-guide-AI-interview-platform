package com.example.interview.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.ChatOptions;
import reactor.core.publisher.Flux;

import java.util.List;

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
        throw new IllegalStateException("所有 AI 模型均调用失败", last);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        Flux<ChatResponse> flux = Flux.defer(() -> delegates.get(0).stream(prompt));
        for (int i = 1; i < delegates.size(); i++) {
            final int idx = i;
            flux = flux.onErrorResume(e -> {
                log.warn("AI 模型 {} 流式调用失败，尝试降级：{}", names.get(idx), e.getMessage());
                return Flux.defer(() -> delegates.get(idx).stream(prompt));
            });
        }
        return flux;
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
