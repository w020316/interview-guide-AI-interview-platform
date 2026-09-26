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

    /**
     * AI 调用耗时窗口（进程内自算百分位）。
     *
     * <p>2026-09-26（第三轮 P2-03）：线上 {@code ai.call.duration} 的客户端百分位长期恒为 0，
     * 8 组对照实验证明「配置 / 注册表 / 取值方式 / Spring 装配」全部正确、根因未能在进程内复现
     * （详见 {@link AiLatencyWindow} 类注释）。故改为自己算，行为可单测、可解释。
     */
    @Bean
    public AiLatencyWindow aiLatencyWindow() {
        return new AiLatencyWindow();
    }

    @Bean
    public Timer aiCallTimer(MeterRegistry registry, AiLatencyWindow aiLatencyWindow) {
        Timer registered = Timer.builder("ai.call.duration")
                .description("AI 接口响应时间")
                .publishPercentiles(0.5, 0.95, 0.99)
                // 保留这段配置：Prometheus 侧仍需要 SLO 边界与百分位直方图，
                // 而且它本身是正确的（对照实验已验证），只是客户端 percentile() 在线上读不到值。
                .minimumExpectedValue(java.time.Duration.ofMillis(100))
                .maximumExpectedValue(java.time.Duration.ofMinutes(3))
                .register(registry);
        // 装饰：任何注入 Timer 的调用点 record 时，都会同时写进耗时窗口，
        // 避免在 8 个埋点处各加一行、留下一堆可能被漏掉的位置。
        return new LatencyAwareTimer(registered, aiLatencyWindow);
    }
}
