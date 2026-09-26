package com.example.interview.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.concurrent.TimeUnit;

/**
 * 【探针 2】把 {@link MetricsConfig} 放进真实 Spring 容器装配，再走一遍「注入 Timer → 记录 → find() 取回 → 读 p95」。
 *
 * <p>前一个探针（{@link PercentileProbeTest}）已证明：手工 new 出来的 Timer，
 * 无论用 SimpleMeterRegistry 还是 PrometheusMeterRegistry、无论直接持有还是 find() 取回，
 * p95 都正常。于是剩下唯一的嫌疑是 **Spring 装配环节**（公共 tag、MeterFilter、Bean 作用域等）。
 * 本探针就是针对这一环的对照实验。
 */
@DisplayName("探针2：Spring 容器装配后 p95 是否仍正常")
class PercentileSpringProbeTest {

    private static final long MS = 1_000_000L;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MetricsConfig.class)
            .withBean(MeterRegistry.class,
                    () -> new PrometheusMeterRegistry(PrometheusConfig.DEFAULT));

    @Test
    @DisplayName("注入 MetricsConfig 的 Timer，记录 3 个已知样本后读 p95")
    void springWiredTimer() {
        runner.run(ctx -> {
            Timer injected = ctx.getBean("aiCallTimer", Timer.class);
            injected.record(100 * MS, TimeUnit.NANOSECONDS);
            injected.record(1000 * MS, TimeUnit.NANOSECONDS);
            injected.record(10000 * MS, TimeUnit.NANOSECONDS);

            MeterRegistry registry = ctx.getBean(MeterRegistry.class);
            Timer viaFind = registry.find("ai.call.duration").timer();

            System.out.println("========== 探针2：Spring 装配后 ==========");
            System.out.printf("注入的 Timer   : count=%d mean=%.1fms p95=%.1f%n",
                    injected.count(), injected.mean(TimeUnit.MILLISECONDS),
                    injected.percentile(0.95, TimeUnit.MILLISECONDS));
            System.out.printf("find() 取回的  : count=%d mean=%.1fms p95=%.1f%n",
                    viaFind.count(), viaFind.mean(TimeUnit.MILLISECONDS),
                    viaFind.percentile(0.95, TimeUnit.MILLISECONDS));
            System.out.println("注册表里的 meter id: " + injected.getId());
            System.out.println("=========================================");

            // 回归断言：Spring 装配后注入的 Timer 与 find() 取回的 Timer 都要有非 0 P95
            assertThat(injected.percentile(0.95, TimeUnit.MILLISECONDS)).isGreaterThan(0.0);
            assertThat(viaFind.percentile(0.95, TimeUnit.MILLISECONDS)).isGreaterThan(0.0);
        });
    }

    @Test
    @DisplayName("带上生产同款公共 tag（management.metrics.tags.application）再测")
    void withCommonTag() {
        new ApplicationContextRunner()
                .withUserConfiguration(MetricsConfig.class)
                .withBean(MeterRegistry.class, () -> {
                    PrometheusMeterRegistry r = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
                    r.config().commonTags("application", "interview-guide");
                    return r;
                })
                .run(ctx -> {
                    Timer injected = ctx.getBean("aiCallTimer", Timer.class);
                    injected.record(100 * MS, TimeUnit.NANOSECONDS);
                    injected.record(1000 * MS, TimeUnit.NANOSECONDS);
                    injected.record(10000 * MS, TimeUnit.NANOSECONDS);
                    System.out.println("===== 探针2b：带公共 tag =====");
                    System.out.printf("p95=%.1f  id=%s%n",
                            injected.percentile(0.95, TimeUnit.MILLISECONDS), injected.getId());
                    System.out.println("=============================");
                    assertThat(injected.percentile(0.95, TimeUnit.MILLISECONDS)).isGreaterThan(0.0);
                });
    }
}
