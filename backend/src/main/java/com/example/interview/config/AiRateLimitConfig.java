package com.example.interview.config;

import com.example.interview.filter.AiRateLimitFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 端点限流的装配（P1-4）。
 *
 * <h2>为什么单独一个 {@code @Configuration}，而不是写成 {@code @Component} 的过滤器</h2>
 *
 * <p>出于两点考虑：
 * <ol>
 *   <li><b>配额可分别调</b>：普通 AI 端点与 Agent（ReAct 循环，单请求 8 轮）成本差一个量级，
 *       必须能独立配置，不能用一个全局值糊过去；</li>
 *   <li><b>不污染切片测试</b>：{@code @WebMvcTest} 会加载 {@code WebMvcConfigurer} 与
 *       {@code Filter} 类型的 Bean。本类只是普通 {@code @Configuration}，不会被切片加载，
 *       因此既有的 38 个控制器测试无需改造就继续是「无过滤器」的纯净切片。</li>
 * </ol>
 *
 * <h2>配置项（{@code application.yml} 的 {@code app.ai.user-rate-limit}）</h2>
 *
 * <pre>
 *   enabled            true    总开关
 *   paths              —       受限流的 AI 路径前缀（逗号分隔）
 *   requests           60      普通 AI 端点每窗口配额
 *   expensive-paths    /api/agent/   成本高一个量级的路径前缀
 *   expensive-requests 20      高成本端点每窗口配额
 *   window-seconds     60      窗口长度（滑动窗口）
 * </pre>
 *
 * <p>默认值在这里与 {@code application.yml} 各写一份：配置缺失时以注解默认值兜底，
 * 保证「改配置文件」与「删掉配置项」都不会让应用起不来。
 */
@Configuration
public class AiRateLimitConfig {

    /**
     * 创建 AI 端点限流过滤器。
     *
     * <p>返回的 Bean 会被 Spring Boot 自动注册为 Servlet 过滤器；执行顺序由
     * {@link AiRateLimitFilter#getOrder()} 决定（必须晚于 Spring Security 链，才能读到 userId）。
     */
    @Bean
    public AiRateLimitFilter aiRateLimitFilter(
            @Value("${app.ai.user-rate-limit.enabled:true}") boolean enabled,
            @Value("${app.ai.user-rate-limit.paths:" + AiRateLimitFilter.DEFAULT_AI_PATH_PREFIXES + "}")
            String aiPathPrefixes,
            @Value("${app.ai.user-rate-limit.requests:60}") int requests,
            @Value("${app.ai.user-rate-limit.expensive-paths:"
                    + AiRateLimitFilter.DEFAULT_EXPENSIVE_PATH_PREFIXES + "}")
            String expensivePathPrefixes,
            @Value("${app.ai.user-rate-limit.expensive-requests:20}") int expensiveRequests,
            @Value("${app.ai.user-rate-limit.window-seconds:60}") long windowSeconds,
            ObjectMapper objectMapper) {
        return new AiRateLimitFilter(enabled, aiPathPrefixes, requests,
                expensivePathPrefixes, expensiveRequests, windowSeconds, objectMapper);
    }
}
