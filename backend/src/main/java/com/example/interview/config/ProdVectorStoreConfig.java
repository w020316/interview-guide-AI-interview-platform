package com.example.interview.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 生产环境向量库配置
 * - 用 SimpleVectorStore（内存实现）替代 pgvector
 * - 标记为 @Primary 确保优先使用此实现
 * - RAG 功能降级，不影响核心面试功能
 */
@Configuration
public class ProdVectorStoreConfig {

    @Bean
    @Primary
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}
