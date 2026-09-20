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
        String lastDetail = null;
        for (int i = 0; i < delegates.size(); i++) {
            try {
                ChatResponse resp = delegates.get(i).call(prompt);
                // P1（2026-09-20 免费模型实测发现）：把「HTTP 200 但正文为空」判定为失败并继续降级。
                //
                // 背景：智谱 glm-4.7-flash / glm-4.5-flash 默认开启思考模式，reasoning_content
                // 会吃光 max_tokens，导致 content 返回空字符串。此时 HTTP 200、usage 正常，
                // Spring AI 不抛异常 —— 若照原样返回，用户会拿到一个**成功状态下的空白答案**，
                // 比直接报错更糟（前端无法区分"没答"与"答了但空"）。
                //
                // 这里主动拦截：正文为空即视为该节点不可用，降级到下一顺位。
                // 若全部节点都空，则走下方统一失败路径（503 + 可读文案），而不是返回空答案。
                if (!hasUsableContent(resp)) {
                    String detail = "返回内容为空（疑似思考模式耗尽 max_tokens）";
                    log.warn("AI 模型 {} 判定不可用：{}", names.get(i), detail);
                    lastDetail = names.get(i) + " → " + detail;
                    continue;
                }
                if (i > 0) {
                    log.warn("已降级到第 {} 顺位模型 {}", i + 1, names.get(i));
                }
                return resp;
            } catch (Exception e) {
                String detail = describeFailure(e);
                log.warn("AI 模型 {} 调用失败，尝试降级：{}", names.get(i), detail);
                last = e;
                lastDetail = names.get(i) + " → " + detail;
            }
        }
        // U1：降级链全失败属于"AI 服务暂不可用"的可重试业务故障，以 BusinessException 承载
        // 明确文案（全局处理器映射 503），替代此前语义混淆的 IllegalStateException→500"服务器内部错误"
        //
        // P2（2026-09-19 真机验证发现）：此前无论失败原因是什么，用户都只看到「AI 服务暂时不可用」，
        // 运维在日志里也只能看到「Error while extracting response ...」这种被 Spring 包装后的
        // 无信息量文案，完全无法区分「密钥失效」「模型名写错」「额度耗尽」这三类需要人工介入的
        // 配置故障与真正的「上游临时抖动」。
        // 典型来源：上游以 HTTP 200 返回 {"error":{"message":"Invalid token"}}（而非 401），
        // Spring AI 拿 200 去反序列化 ChatCompletion 结构失败，抛出的异常消息里不含上游原文。
        // 现把每个节点的失败摘要拼进日志，并让业务异常携带可诊断的一手信息。
        log.error("AI 降级链全部失败（共 {} 个节点）。失败摘要：{}", delegates.size(), lastDetail);
        throw new BusinessException("AI 服务暂时不可用，请稍后重试", last);
    }

    /**
     * 判断一次响应是否含可用正文。
     *
     * <p>用于拦截「HTTP 200 + 空 content」这种**假成功**：模型返回了响应对象、usage 也正常，
     * 但真正给用户看的正文是空的（典型成因是思考模式的 reasoning_content 占满输出预算）。
     * 这种情况必须继续降级，绝不能把空答案当成功返回给业务层。
     *
     * <p>空值处理：响应结构任一层为 null（无 result / 无 output / 无 message）一律判为不可用。
     */
    static boolean hasUsableContent(ChatResponse resp) {
        if (resp == null || resp.getResult() == null || resp.getResult().getOutput() == null) {
            return false;
        }
        String text = resp.getResult().getOutput().getText();
        return text != null && !text.isBlank();
    }

    /**
     * 提取可诊断的失败原因。
     *
     * <p>Spring AI 对「HTTP 200 + {"error":{...}}」这类非标准错误响应，会抛出
     * {@code RestClientException: Error while extracting response ...}，异常消息本身不含上游原文；
     * 真实原因藏在 {@code HttpMessageNotReadableException} 的 cause 里（Jackson 解析错误会带上
     * 原始 JSON 片段）。这里沿 cause 链向下找，把最有信息量的一层提取出来，
     * 使「Invalid token」「model not found」「insufficient quota」等配置类故障直接可见。
     */
    static String describeFailure(Throwable e) {
        String best = null;
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 8) {
            String msg = cur.getMessage();
            if (msg != null && !msg.isBlank()) {
                // 优先选择包含上游错误关键字的层——这些才是有诊断价值的
                if (msg.contains("Invalid token") || msg.contains("invalid_api_key")
                        || msg.contains("model") || msg.contains("quota") || msg.contains("balance")
                        || msg.contains("Unauthorized") || msg.contains("rate limit")
                        || msg.contains("error\":")) {
                    best = msg;
                    break;
                }
                if (best == null) {
                    best = msg;
                }
            }
            cur = cur.getCause();
            depth++;
        }
        if (best == null) {
            return e.getClass().getSimpleName();
        }
        // 截断超长消息，避免日志被大段 JSON 淹没
        return best.length() > 500 ? best.substring(0, 500) + "..." : best;
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
                        log.warn("AI 模型 {} 流式输出中途失败（已发出 token，不降级）：{}",
                                names.get(idx - 1), describeFailure(e));
                        return Flux.error(e);
                    }
                    log.warn("AI 模型 {} 流式调用失败（未发出 token），尝试降级：{}",
                            names.get(idx - 1), describeFailure(e));
                    Flux<ChatResponse> next = Flux.defer(() -> delegates.get(idx).stream(prompt))
                            .doOnNext(resp -> firstTokenSent.set(true));
                    return next.onErrorResume(inner -> {
                        log.error("AI 模型 {} 流式降级后仍失败：{}",
                                names.get(idx), describeFailure(inner));
                        return Flux.error(inner);
                    });
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
