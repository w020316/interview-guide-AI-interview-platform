package com.example.interview.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 密钥空白字符清洗单测（v1.33.1 扩展回归）
 *
 * <p>真实故障形态：在 Render 控制台粘贴 API Key 时末尾带入换行符，Spring AI 构造
 * {@code Authorization: Bearer sk-xxx\n} 触发 {@code Illegal character(s) in message header value}，
 * 该提供方全部请求 400 失败。在多厂商降级链下，这会被静默吞掉并降级到下一个模型，
 * 表现为「配置了主模型却不生效」，排查成本极高。
 *
 * <p>本测试锁定：清单内的变量被清理、清单外的不受影响、无污染时不注入覆盖源。
 */
@DisplayName("AI 密钥空白字符清洗（EnvironmentPostProcessor）")
class AiKeySanitizerPostProcessorTest {

    private static StandardEnvironment envWith(Map<String, Object> props) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("testDirtyValues", props));
        return env;
    }

    private static void run(StandardEnvironment env) {
        new AiKeySanitizerPostProcessor().postProcessEnvironment(
                env, new SpringApplication(AiKeySanitizerPostProcessorTest.class));
    }

    @Test
    @DisplayName("sanitize：移除任意位置的 \\r \\n 并 trim")
    void sanitize_removesIllegalCharacters() {
        assertThat(AiKeySanitizerPostProcessor.sanitize("sk-abc\n")).isEqualTo("sk-abc");
        assertThat(AiKeySanitizerPostProcessor.sanitize("  sk-abc  ")).isEqualTo("sk-abc");
        assertThat(AiKeySanitizerPostProcessor.sanitize("sk-ab\r\nc")).isEqualTo("sk-abc");
        assertThat(AiKeySanitizerPostProcessor.sanitize("sk-abc")).isEqualTo("sk-abc");
    }

    @Test
    @DisplayName("降级链与 Embedding 密钥（render.yaml 中 sync:false 的人工粘贴项）均被清理")
    void postProcess_cleansChainAndEmbeddingKeys() {
        Map<String, Object> dirty = new HashMap<>();
        dirty.put("AI_API_KEY", "sk-agnes\n");
        dirty.put("AI_BAI_API_KEY", " sk-bai \r\n");
        dirty.put("AI_EMBEDDING_API_KEY", "sk-silicon\r");
        dirty.put("AI_EMBEDDING_BASE_URL", "https://api.siliconflow.cn/v1\n");
        dirty.put("spring.ai.openai.api-key", "sk-openai\n");
        StandardEnvironment env = envWith(dirty);

        run(env);

        assertThat(env.getProperty("AI_API_KEY")).isEqualTo("sk-agnes");
        assertThat(env.getProperty("AI_BAI_API_KEY")).isEqualTo("sk-bai");
        assertThat(env.getProperty("AI_EMBEDDING_API_KEY")).isEqualTo("sk-silicon");
        assertThat(env.getProperty("AI_EMBEDDING_BASE_URL")).isEqualTo("https://api.siliconflow.cn/v1");
        assertThat(env.getProperty("spring.ai.openai.api-key")).isEqualTo("sk-openai");
    }

    @Test
    @DisplayName("清单外的变量不受影响（避免误改无关配置）")
    void postProcess_ignoresUnrelatedKeys() {
        Map<String, Object> props = new HashMap<>();
        props.put("AI_API_KEY", "sk-ok");
        props.put("UNRELATED_KEY", "value\n");
        props.put("JWT_SECRET", "secret\n");
        StandardEnvironment env = envWith(props);

        run(env);

        assertThat(env.getProperty("UNRELATED_KEY")).isEqualTo("value\n");
        assertThat(env.getProperty("JWT_SECRET")).isEqualTo("secret\n");
    }

    @Test
    @DisplayName("值本身干净时不注入覆盖源（幂等、不产生无谓的属性源）")
    void postProcess_cleanValues_noOverrideSource() {
        Map<String, Object> props = new HashMap<>();
        props.put("AI_API_KEY", "sk-clean");
        StandardEnvironment env = envWith(props);

        run(env);

        assertThat(env.getPropertySources().contains("aiKeySanitizer")).isFalse();
        assertThat(env.getProperty("AI_API_KEY")).isEqualTo("sk-clean");
    }
}
