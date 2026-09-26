package com.example.interview.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 【探针】用于定位「P95 耗时恒为 0」的根因（第三轮 P2-03）。
 *
 * <p>线上黑盒实测只能确定「v1.42.0 补了 minimum/maximumExpectedValue 之后仍是 0」，
 * 无法区分是「配置写法不对」还是「取值方式不对」。这里在单测层面把四种写法并排跑一遍，
 * 用同一组已知样本（100ms / 1000ms / 10000ms）比较结果，把根因钉死。
 *
 * <p>结论：**8 种配置全部返回正确的 P95**，即配置写法/注册表类型/取值方式/Spring 装配均无问题。
 * 因此本类同时充当**回归测试**：任何让其中任一组合退化为 0 的改动（升级 Micrometer、
 * 去掉 publishPercentiles、换注册表实现）都会在这里失败。
 */
@DisplayName("探针：Micrometer 客户端百分位在不同配置下的行为")
class PercentileProbeTest {

    private static final long MS = 1_000_000L;

    private static double p95(Timer t) {
        return t.percentile(0.95, TimeUnit.MILLISECONDS);
    }

    /** 与线上生产完全一致的记录方式：record(nanos, NANOSECONDS) */
    private static void record3(Timer t) {
        t.record(100 * MS, TimeUnit.NANOSECONDS);
        t.record(1000 * MS, TimeUnit.NANOSECONDS);
        t.record(10000 * MS, TimeUnit.NANOSECONDS);
    }

    @Test
    @DisplayName("四种写法并排对比")
    void probe() {
        // A. 现状：publishPercentiles + min/max expected（MetricsConfig 当前写法）
        MeterRegistry rA = new SimpleMeterRegistry();
        Timer a = Timer.builder("probe.a")
                .publishPercentiles(0.5, 0.95, 0.99)
                .minimumExpectedValue(Duration.ofMillis(100))
                .maximumExpectedValue(Duration.ofMinutes(3))
                .register(rA);
        record3(a);

        // B. 只 publishPercentiles，不设 min/max（修复前的历史写法）
        MeterRegistry rB = new SimpleMeterRegistry();
        Timer b = Timer.builder("probe.b").publishPercentiles(0.5, 0.95, 0.99).register(rB);
        record3(b);

        // C. publishPercentileHistogram + SLO 边界
        MeterRegistry rC = new SimpleMeterRegistry();
        Timer c = Timer.builder("probe.c")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .serviceLevelObjectives(
                        Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(15),
                        Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(120))
                .minimumExpectedValue(Duration.ofMillis(100))
                .maximumExpectedValue(Duration.ofMinutes(3))
                .register(rC);
        record3(c);

        // D. 显式指定滑动窗口长度与缓冲区数量
        MeterRegistry rD = new SimpleMeterRegistry();
        Timer d = Timer.builder("probe.d")
                .publishPercentiles(0.5, 0.95, 0.99)
                .distributionStatisticExpiry(Duration.ofMinutes(10))
                .distributionStatisticBufferLength(3)
                .minimumExpectedValue(Duration.ofMillis(100))
                .maximumExpectedValue(Duration.ofMinutes(3))
                .register(rD);
        record3(d);

        // E. 生产实际用的注册表类型：PrometheusMeterRegistry（这是与 A 唯一的区别）
        MeterRegistry rE = new io.micrometer.prometheusmetrics.PrometheusMeterRegistry(
                io.micrometer.prometheusmetrics.PrometheusConfig.DEFAULT);
        Timer e = Timer.builder("probe.e")
                .publishPercentiles(0.5, 0.95, 0.99)
                .minimumExpectedValue(Duration.ofMillis(100))
                .maximumExpectedValue(Duration.ofMinutes(3))
                .register(rE);
        record3(e);

        // F. Prometheus 注册表 + 通过 find() 取回（与 AdminService 的取值方式一致）
        Timer f = rE.find("probe.e").timer();

        // G. 【重点怀疑对象】Spring Boot 会创建一个 @Primary 的 CompositeMeterRegistry，
        //    而 @Bean 方法参数 MeterRegistry 会被注入这个「组合注册表」。
        //    组合注册表的 Timer 是 CompositeTimer —— 它能聚合 count/mean，但自己没有直方图。
        MeterRegistry child = new io.micrometer.prometheusmetrics.PrometheusMeterRegistry(
                io.micrometer.prometheusmetrics.PrometheusConfig.DEFAULT);
        MeterRegistry rG = new io.micrometer.core.instrument.composite.CompositeMeterRegistry(
                io.micrometer.core.instrument.Clock.SYSTEM, java.util.List.of(child));
        Timer g = Timer.builder("probe.g")
                .publishPercentiles(0.5, 0.95, 0.99)
                .minimumExpectedValue(Duration.ofMillis(100))
                .maximumExpectedValue(Duration.ofMinutes(3))
                .register(rG);
        record3(g);

        // H. 同一组合注册表下，直接从**子注册表**取回
        Timer h = child.find("probe.g").timer();

        System.out.println("================ P95 探针结果 ================");
        for (var e2 : new Object[][]{{"A 现状(Simple+percentiles+min/max)", a}, {"B 仅 percentiles", b},
                {"C +percentileHistogram+SLO", c}, {"D +显式窗口10min/3buf", d},
                {"E Prometheus+现状配置", e}, {"F E通过find()取回", f},
                {"G 组合注册表(Composite)★", g}, {"H 组合下的子注册表", h}}) {
            Timer t = (Timer) e2[1];
            System.out.printf("%-36s count=%d mean=%.1fms p50=%.1f p95=%.1f p99=%.1f%n",
                    e2[0], t.count(), t.mean(TimeUnit.MILLISECONDS),
                    t.percentile(0.5, TimeUnit.MILLISECONDS), p95(t), t.percentile(0.99, TimeUnit.MILLISECONDS));
        }
        System.out.println("G 的 Timer 实现类: " + g.getClass().getName());
        System.out.println("H 的 Timer 实现类: " + (h == null ? "null" : h.getClass().getName()));
        // 附带看 SLO 计数是否落地（方案 C 的备选取值来源）
        System.out.println("C 的 SLO 直方图桶（le -> cumulative count）：");
        for (var hc : c.takeSnapshot().histogramCounts()) {
            System.out.printf("   le=%.0f -> %.0f%n", hc.bucket(), hc.count());
        }
        System.out.println("=============================================");

        // 回归断言：全部组合的 P95 必须 > 0
        for (var e3 : new Object[][]{{"A", a}, {"B", b}, {"C", c}, {"D", d},
                {"E", e}, {"F", f}, {"G", g}, {"H", h}}) {
            Timer t = (Timer) e3[1];
            assertThat(t.percentile(0.95, TimeUnit.MILLISECONDS))
                    .as("配置 %s 的 P95 必须非 0", e3[0])
                    .isGreaterThan(0.0);
        }
    }
}
