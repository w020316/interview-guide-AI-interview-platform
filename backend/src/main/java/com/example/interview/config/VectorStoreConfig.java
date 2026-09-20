package com.example.interview.config;

import com.example.interview.ai.PersistentSimpleVectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 本地联调向量库配置
 * - 仅在 profile=local 时生效
 * - 用 {@link PersistentSimpleVectorStore}（SimpleVectorStore + 文件快照）替代 pgvector，
 *   避免依赖 PostgreSQL + pgvector
 *
 * v1.34.0（2026-09-20）：「电脑关机后 RAG 不可用」修复。
 * 原实现为纯内存 {@code SimpleVectorStore}，进程退出即清空，只能靠每次启动重新播种预置知识，
 * 用户导入的知识与自动补充的知识全部丢失。现改为带文件快照的实现：
 * 启动恢复、变更异步落盘、关闭兜底，落盘逻辑见 {@link VectorStorePersistenceManager}。
 *
 * 关闭持久化：{@code app.rag.persist-enabled=false}（退回纯内存，行为与改造前一致）。
 */
@Configuration
@Profile("local")
public class VectorStoreConfig {

    @Bean
    public PersistentSimpleVectorStore vectorStore(
            EmbeddingModel embeddingModel,
            @Value("${app.rag.snapshot-file:D:/xm/data/vectorstore.json}") String snapshotPath) {
        return new PersistentSimpleVectorStore(embeddingModel, snapshotPath);
    }
}
