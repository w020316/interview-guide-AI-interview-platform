package com.example.interview.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 环境后置处理器：清理 Spring AI API Key 中的非法空白字符。
 *
 * <p>背景：在 Render 等云平台配置环境变量时，API Key 值末尾常被意外带入换行符（\n、\r）或空格，
 * 导致 Spring AI 构造的 HTTP 请求头 {@code Authorization: Bearer sk-xxx\n} 含非法字符，
 * 触发 {@code Illegal character(s) in message header value} 异常，AI 接口全部 400 失败。</p>
 *
 * <p>本处理器在 Spring AI 自动装配前介入，对相关属性值做 trim，从根源消除问题。</p>
 */
public class AiKeySanitizerPostProcessor implements EnvironmentPostProcessor {

    private static final String[] KEYS_TO_TRIM = {
            "spring.ai.openai.api-key",
            "spring.ai.openai.base-url",
            "AI_API_KEY",
            "AI_BASE_URL",
            "AI_MODEL"
    };

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> overrides = new HashMap<>();
        for (String key : KEYS_TO_TRIM) {
            String value = environment.getProperty(key);
            if (StringUtils.hasText(value)) {
                String trimmed = value.trim();
                if (!trimmed.equals(value)) {
                    overrides.put(key, trimmed);
                }
            }
        }
        if (!overrides.isEmpty()) {
            // 高优先级覆盖，确保后续装配读到的是清理后的值
            environment.getPropertySources()
                    .addFirst(new MapPropertySource("aiKeySanitizer", overrides));
        }
    }
}
