package com.example.interview.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.support.HttpRequestWrapper;

import java.io.IOException;

/**
 * 在请求体里显式关闭「思考模式」（{@code thinking:{"type":"disabled"}}）的拦截器。
 *
 * ── 为什么需要它（2026-09-26 线上实测）──────────────────────────────
 * 智谱 GLM 系列默认开启思考模式，{@code reasoning_content} 会吃光 {@code max_tokens}，
 * 表现为**HTTP 200 但 {@code content} 为空**（实测 {@code glm-4.7-flash}：
 * {@code content=0}、{@code reasoning_content=358}、{@code finish_reason=length}）。
 *
 * 本项目 {@code FallbackChatModel} 有一段刻意的保护：把「HTTP 200 但正文为空」判为该节点
 * 不可用并继续降级（避免把空答案当成功下发）。两者叠加的后果是——
 * **兜底节点每次都被静默跳过**：跨厂商容灾退化为单点，且每次主模型故障都要白撞一次
 * （多 4.25s 延迟 + 一次免费额度）。
 *
 * 实测关闭思考后的同一问题：{@code glm-4.7-flash} 由 4.25s/空内容 → **1.38s/正常内容**；
 * {@code glm-4.5-flash} / {@code glm-4-flash} / {@code glm-4-flash-250414} 也都接受该字段
 * 并返回正常内容，因此可以安全地对整个厂商统一开启。
 *
 * ── 为什么用拦截器而不是配置项 ──────────────────────────────────────
 * Spring AI 1.0.0 的 {@code OpenAiChatOptions} **没有 {@code extraBody}**
 * （已对 jar 做常量池探针确认），无法通过 options 下发厂商私有字段，
 * 只能在请求发出前改写 JSON 请求体。
 *
 * ── 行为 ──────────────────────────────────────────────────────────
 * - 请求体不是 JSON 对象 → 原样放行（零副作用）
 * - 请求体已有 {@code thinking} 字段 → 原样放行（尊重调用方的显式设置）
 * - 其余情况 → 注入 {@code "thinking":{"type":"disabled"}}，并同步修正 {@code Content-Length}
 */
public class DisableThinkingInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(DisableThinkingInterceptor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        if (body == null || body.length == 0) {
            return execution.execute(request, body);
        }

        ObjectNode node;
        try {
            var parsed = MAPPER.readTree(body);
            if (parsed == null || !parsed.isObject()) {
                return execution.execute(request, body);
            }
            node = (ObjectNode) parsed;
        } catch (IOException e) {
            // 不是 JSON（例如非对话类请求）：不碰它
            return execution.execute(request, body);
        }

        if (node.has("thinking")) {
            // 调用方已显式指定，尊重之
            return execution.execute(request, body);
        }

        ObjectNode thinking = MAPPER.createObjectNode();
        thinking.put("type", "disabled");
        node.set("thinking", thinking);
        byte[] rewritten = MAPPER.writeValueAsBytes(node);

        // 必须同步修正 Content-Length：请求体变长了，沿用旧长度会被截断或读越界
        HttpRequestWrapper wrapper = new HttpRequestWrapper(request);
        wrapper.getHeaders().setContentLength(rewritten.length);

        log.debug("已为 AI 请求注入 thinking=disabled（原 {} 字节 → {} 字节）",
                body.length, rewritten.length);
        return execution.execute(wrapper, rewritten);
    }
}
