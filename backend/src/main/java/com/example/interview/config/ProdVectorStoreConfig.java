package com.example.interview.config;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.List;

/**
 * 轻量 VectorStore 兜底实现
 * - 当 pgvector 自动装配被排除时（生产环境 512MB 容器），提供 no-op 实现
 * - 使用 @ConditionalOnMissingBean 确保只在没有其他 VectorStore bean 时生效
 * - RAG 功能降级为纯 AI 问答，不影响核心面试功能
 */
@Configuration
public class ProdVectorStoreConfig {

    @Bean
    @ConditionalOnMissingBean(VectorStore.class)
    public VectorStore vectorStore() {
        return new VectorStore() {
            @Override
            public void add(List<Document> documents) {
                // no-op
            }

            @Override
            public boolean delete(List<String> ids) {
                return true;
            }

            @Override
            public List<Document> similaritySearch(SearchRequest searchRequest) {
                return Collections.emptyList();
            }
        };
    }
}
