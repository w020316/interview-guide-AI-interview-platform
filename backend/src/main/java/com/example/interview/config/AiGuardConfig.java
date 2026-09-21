package com.example.interview.config;

import com.example.interview.ai.AiConcurrencyGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * AI 并发闸门配置装配（v1.34.1，P3-8）
 *
 * <p><b>解决的问题</b>：{@link AiConcurrencyGuard} 是静态工具类（无 Spring 依赖，便于被任意层调用），
 * 其排队超时此前**只能**通过系统属性 {@code -Dapp.ai.guard-acquire-timeout-seconds=...} 覆盖，
 * 无法在 {@code application.yml} 中配置——容器化部署（如 Render/Docker）调整该值时不得不改启动参数。
 *
 * <p>本类在应用启动时把配置值注入静态字段，优先级：
 * <ol>
 *   <li>{@code app.ai.guard-acquire-timeout-seconds}（yml / 环境变量，推荐）</li>
 *   <li>同名系统属性（兼容原有部署方式）</li>
 *   <li>内置默认 30 秒</li>
 * </ol>
 * 非正值一律忽略，避免误配把超时置 0 导致所有 AI 调用立即失败。
 */
@Configuration
public class AiGuardConfig {

    private static final Logger log = LoggerFactory.getLogger(AiGuardConfig.class);

    public AiGuardConfig(
            @Value("${app.ai.guard-acquire-timeout-seconds:0}") long acquireTimeoutSeconds) {
        if (acquireTimeoutSeconds > 0) {
            AiConcurrencyGuard.setAcquireTimeoutSeconds(acquireTimeoutSeconds);
        }
        log.info("AI 并发闸门排队超时 = {} 秒（前台许可 {}, 后台许可 {}）",
                AiConcurrencyGuard.acquireTimeoutSeconds(),
                AiConcurrencyGuard.availablePermits(),
                AiConcurrencyGuard.availableBackgroundPermits());
    }
}
