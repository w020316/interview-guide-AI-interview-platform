package com.example.interview.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 环境后置处理器：清理 AI 相关配置值中的非法空白字符。
 *
 * <p>背景：在 Render 等云平台配置环境变量时，API Key 值末尾常被意外带入换行符（\n、\r）或空格，
 * 导致 Spring AI 构造的 HTTP 请求头 {@code Authorization: Bearer sk-xxx\n} 含非法字符，
 * 触发 {@code Illegal character(s) in message header value} 异常，AI 接口全部 400 失败。</p>
 *
 * <p>本处理器在 Spring AI 自动装配前介入，对相关属性值做清理，从根源消除问题。</p>
 *
 * <p>v1.33.1（2026-09-19）扩展：原清单只覆盖 {@code spring.ai.openai.*} 与 Agnes 三个变量，
 * 而 v1.22.0 起聊天模型已切换为 {@code app.ai.chain} 多厂商降级链，其密钥来自
 * {@code AI_BAI_API_KEY} / {@code AI_EMBEDDING_API_KEY} 等——这些在 render.yaml 中均为
 * {@code sync: false}（需人工复制粘贴到控制台），恰恰是换行污染的高发场景。
 * 因此把全部 AI 相关变量纳入清理，否则「主模型因密钥带换行而 401 → 静默降级到 Agnes」
 * 会表现为「模型降级链配置了却不生效」，极难排查。</p>
 */
public class AiKeySanitizerPostProcessor implements EnvironmentPostProcessor {

    /** 需要清理空白字符的配置项（属性名 + 环境变量名两条通道都覆盖） */
    private static final String[] KEYS_TO_TRIM = {
            // ── Spring AI 自动装配（Embedding 仍走这里）──
            "spring.ai.openai.api-key",
            "spring.ai.openai.base-url",
            // ── Agnes AI（兜底聊天模型 + 历史配置项）──
            "AI_API_KEY",
            "AI_BASE_URL",
            "AI_MODEL",
            "AI_CHAT_MODEL",
            "AI_EMBEDDING_MODEL",
            // ── B.AI 降级链（v1.22.0 主/次模型）──
            "AI_BAI_API_KEY",
            "AI_BAI_BASE_URL",
            "AI_BAI_CHAT_MODEL",
            "AI_BAI_CHAT_MODEL_SECONDARY",
            // ── Embedding 第三方提供方（v1.33.0 可插拔，如硅基流动 bge-m3）──
            "AI_EMBEDDING_BASE_URL",
            "AI_EMBEDDING_API_KEY",
            "AI_EMBEDDING_DIMENSIONS",
    };

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> overrides = new LinkedHashMap<>();
        for (String key : KEYS_TO_TRIM) {
            String value = environment.getProperty(key);
            if (StringUtils.hasText(value)) {
                String cleaned = sanitize(value);
                if (!cleaned.equals(value)) {
                    overrides.put(key, cleaned);
                }
            }
        }
        if (!overrides.isEmpty()) {
            // 高优先级覆盖，确保后续装配读到的是清理后的值
            environment.getPropertySources().addFirst(new MapPropertySource("aiKeySanitizer", new HashMap<>(overrides)));
        }
    }

    /**
     * 去除首尾空白，并移除任意位置的 \r / \n（HTTP 头非法字符）。
     *
     * <p>API Key 与 URL 不存在合法换行，因此整体移除比 trim 更贴合真实污染形态
     * （例如粘贴时被终端折行带入内部换行）。
     */
    static String sanitize(String value) {
        return value.replace("\r", "").replace("\n", "").trim();
    }
}
