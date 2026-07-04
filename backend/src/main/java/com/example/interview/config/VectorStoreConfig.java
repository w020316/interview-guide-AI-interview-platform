package com.example.interview.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 本地联调向量库配置
 * - 仅在 profile=local 时生效
 * - 用 SimpleVectorStore（内存实现）替代 pgvector，避免依赖 PostgreSQL + pgvector
 * - 重启后数据丢失，仅用于本地联调
 */
@Configuration
@Profile("local")
public class VectorStoreConfig {

    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}
