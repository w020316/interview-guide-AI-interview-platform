package com.example.interview.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Prometheus 自定义指标
 * - ai.call.count    AI 调用总次数（resume/question/evaluate 三种类型）
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
                .register(registry);
    }
}
