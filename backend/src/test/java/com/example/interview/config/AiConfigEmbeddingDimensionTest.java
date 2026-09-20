package com.example.interview.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * P2-09 回归测试：证明 {@link AiConfig#openAiEmbeddingModel} 把配置的 embedding 维度
 * 真正带进了 {@link OpenAiEmbeddingOptions}（也即后续 embedding 请求会携带 dimensions 参数）。
 *
 * <p>背景：此前 AiConfig 只设 model、漏设 dimensions，实际向量维度由模型默认值决定，
 * 与 pgvector 表维度（AI_EMBEDDING_DIMENSIONS）可能不一致。
 *
 * <p>这里不经过 Spring 上下文，直接调用生产 @Bean 方法并反射读取其构造出的
 * OpenAiEmbeddingModel 内部的 defaultOptions，验证维度透传，既不发起网络请求也不依赖外部服务。
 */
class AiConfigEmbeddingDimensionTest {

    private static OpenAiEmbeddingOptions extractDefaultOptions(OpenAiEmbeddingModel model) throws Exception {
        Field f = OpenAiEmbeddingModel.class.getDeclaredField("defaultOptions");
        f.setAccessible(true);
        return (OpenAiEmbeddingOptions) f.get(model);
    }

    @Test
    @DisplayName("配置的维度被带进 embedding 请求选项（P2-09 核心修复）")
    void configuredDimensionsAreCarriedIntoEmbeddingOptions() throws Exception {
        AiConfig config = new AiConfig();
        OpenAiEmbeddingModel model = config.openAiEmbeddingModel(
                RestClient.builder(),
                "sk-test",
                "https://api.openai.com/v1",
                "text-embedding-3-small",
                "",
                "",
                1024);

        OpenAiEmbeddingOptions opts = extractDefaultOptions(model);
        assertEquals(1024, opts.getDimensions(),
                "AI_EMBEDDING_DIMENSIONS 必须透传到 embedding 请求选项");
        assertEquals("text-embedding-3-small", opts.getModel());
    }

    @Test
    @DisplayName("embeddingDimensions<=0 时不设置 dimensions（保持原行为，避免提供方拒绝参数）")
    void zeroDimensionsAreNotSet() throws Exception {
        AiConfig config = new AiConfig();
        OpenAiEmbeddingModel model = config.openAiEmbeddingModel(
                RestClient.builder(),
                "sk-test",
                "https://api.openai.com/v1",
                "text-embedding-3-small",
                "",
                "",
                0);

        OpenAiEmbeddingOptions opts = extractDefaultOptions(model);
        assertNull(opts.getDimensions(),
                "embeddingDimensions<=0 时不应设置 dimensions，保持原行为");
    }
}
