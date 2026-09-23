package com.example.interview.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Prometheus 自定义指标
 * - ai.call.count    AI 调用总次数（resume/question/evaluate/jobAnalysis/rag 五种类型）
 * - cache.hit.count  Redis 缓存命中
 * - cache.miss.count Redis 缓存未命中
 * - ai.call.duration AI 响应耗时分布
 */
@Configuration
public class MetricsConfig {

    @Bean
    public Counter aiCallResumeCounter(MeterRegistry registry) {
        return Counter.builder("ai.call.count").tag("type", "resume")
                .description("AI 简历分析调用次数").register(registry);
    }

    @Bean
    public Counter aiCallQuestionCounter(MeterRegistry registry) {
        return Counter.builder("ai.call.count").tag("type", "question")
                .description("AI 面试题生成调用次数").register(registry);
    }

    @Bean
    public Counter aiCallEvaluateCounter(MeterRegistry registry) {
        return Counter.builder("ai.call.count").tag("type", "evaluate")
                .description("AI 回答评估调用次数").register(registry);
    }

    @Bean
    public Counter aiCallJobAnalysisCounter(MeterRegistry registry) {
        return Counter.builder("ai.call.count").tag("type", "jobAnalysis")
                .description("AI 岗位分析调用次数（analyze/gap/letter）").register(registry);
    }

    @Bean
    public Counter aiCallRagCounter(MeterRegistry registry) {
        return Counter.builder("ai.call.count").tag("type", "rag")
                .description("AI RAG 知识库问答调用次数").register(registry);
    }

    @Bean
    public Counter cacheHitCounter(MeterRegistry registry) {
        return Counter.builder("cache.hit.count")
                .description("Redis 缓存命中次数").register(registry);
    }

    @Bean
    public Counter cacheMissCounter(MeterRegistry registry) {
        return Counter.builder("cache.miss.count")
                .description("Redis 缓存未命中次数").register(registry);
    }

    @Bean
    public Timer aiCallTimer(MeterRegistry registry) {
        return Timer.builder("ai.call.duration")
                .description("AI 接口响应时间")
                .publishPercentiles(0.5, 0.95, 0.99)
                // ⚠️ 2026-09-23 修复 P3-A（P95 恒为 0）：
                //   Micrometer 的客户端百分位依赖滑动窗口直方图，而直方图的**取值范围默认上限约 30 秒**；
                //   本项目的 AI 调用耗时常态就在 20~70 秒（出题实测 42s、评分 8~31s、RAG 问答 31.8s），
                //   超界样本会被直接丢弃 → 直方图内无有效样本 → percentile() 恒返回 0，
                //   于是出现「totalCalls=3、avgMs=23515 但 p95Ms=0」这种自相矛盾的指标。
                //   实测印证：触发一次 31.8s 的调用后**立即**查询，P95 仍为 0（排除窗口过期因素）。
                //   显式抬高期望范围到 3 分钟（覆盖长尾），百分位才能落地。
                .minimumExpectedValue(java.time.Duration.ofMillis(100))
                .maximumExpectedValue(java.time.Duration.ofMinutes(3))
                .register(registry);
    }
}
