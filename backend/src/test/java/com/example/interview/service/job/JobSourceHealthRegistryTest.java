package com.example.interview.service.job;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据源健康登记表测试（v1.38.0）
 *
 * <p>这套断言保护的是「运维能否看出数据源挂了」这一能力本身。
 * 其中最容易被写错的两种情况各有专门用例：
 * ① 失败时**不能**抹掉 {@code lastSuccessAt}（否则无法区分「刚挂」与「从未成功」）；
 * ② 冷却判定必须用 {@code lastAttemptAt} 而非成功时间（否则上游故障时会退化成每轮重试）。
 */
@DisplayName("数据源健康登记表测试")
class JobSourceHealthRegistryTest {

    private final JobSourceHealthRegistry registry = new JobSourceHealthRegistry();

    @Test
    @DisplayName("recordSuccess：登记成功并清零连续失败计数")
    void recordSuccess_clearsFailures() {
        registry.recordFailure("测试源", "HTTP 403", 120);
        registry.recordFailure("测试源", "HTTP 403", 130);
        assertThat(registry.get("测试源").consecutiveFailures()).isEqualTo(2);

        registry.recordSuccess("测试源", 42, 300);

        var health = registry.get("测试源");
        assertThat(health.healthy()).isTrue();
        assertThat(health.lastCount()).isEqualTo(42);
        assertThat(health.lastElapsedMs()).isEqualTo(300);
        assertThat(health.lastError()).isNull();
        assertThat(health.consecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("recordFailure：保留 lastSuccessAt，以便区分「刚挂」与「从未成功」")
    void recordFailure_keepsLastSuccessAt() {
        assertThat(registry.get("新源")).isNull();

        registry.recordFailure("新源", "连接超时", 50);
        var first = registry.get("新源");
        assertThat(first.healthy()).isFalse();
        assertThat(first.lastSuccessAt()).as("从未成功过的源").isNull();
        assertThat(first.consecutiveFailures()).isEqualTo(1);

        registry.recordSuccess("新源", 3, 20);
        LocalDateTime successAt = registry.get("新源").lastSuccessAt();

        registry.recordFailure("新源", "HTTP 500", 60);
        var afterFailure = registry.get("新源");
        assertThat(afterFailure.healthy()).isFalse();
        assertThat(afterFailure.lastSuccessAt()).as("失败不应抹掉上次成功时间").isEqualTo(successAt);
        assertThat(afterFailure.consecutiveFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("recordFailure：错误摘要裁剪到 200 字，避免上游整页 HTML 撑爆后台")
    void recordFailure_clipsLongError() {
        registry.recordFailure("长错误源", "x".repeat(5000), 10);
        assertThat(registry.get("长错误源").lastError()).hasSize(200);
    }

    @Test
    @DisplayName("recordFailure：空白错误归为「未知错误」，不留空字符串给前端")
    void recordFailure_blankErrorFallback() {
        registry.recordFailure("空错误源", "   ", 10);
        assertThat(registry.get("空错误源").lastError()).isEqualTo("未知错误");
    }

    @Test
    @DisplayName("snapshot：按平台名排序，保证管理后台展示顺序稳定")
    void snapshot_sorted() {
        registry.recordSuccess("B 源", 1, 1);
        registry.recordSuccess("A 源", 2, 2);
        assertThat(registry.snapshot())
                .extracting(JobSourceHealthRegistry.Health::platform)
                .containsExactly("A 源", "B 源");
    }

    @Test
    @DisplayName("alerts：只报最近失败或连续失败 ≥2 次的源，恢复后自动消失")
    void alerts_filtersHealthySources() {
        registry.recordSuccess("正常源", 10, 100);
        registry.recordFailure("失败源", "HTTP 403", 200);

        assertThat(registry.alerts())
                .extracting(JobSourceHealthRegistry.Health::platform)
                .containsExactly("失败源");

        registry.recordSuccess("失败源", 5, 150);
        assertThat(registry.alerts()).as("恢复后不应继续告警").isEmpty();
    }

    @Test
    @DisplayName("isCoolingDown：按上次尝试时间节流；间隔 ≤0 或从未拉取时不节流")
    void isCoolingDown_throttlesByLastAttempt() {
        assertThat(registry.isCoolingDown("冷却源", 60_000)).as("从未拉取过").isFalse();

        registry.recordFailure("冷却源", "超时", 10);
        assertThat(registry.isCoolingDown("冷却源", 60_000)).isTrue();
        assertThat(registry.isCoolingDown("冷却源", 0)).as("间隔 0 表示不限制").isFalse();
        assertThat(registry.isCoolingDown("冷却源", -1)).isFalse();

        // 关键：失败也计入节流。若改用 lastSuccessAt，上游持续故障时冷却永不生效，
        // 会退化成每小时都重试一个已经挂掉的源
        registry.recordSuccess("成功源", 1, 1);
        assertThat(registry.isCoolingDown("成功源", 60_000)).isTrue();
    }

    @Test
    @DisplayName("null 平台名安全忽略，不产生脏条目")
    void nullPlatformIgnored() {
        registry.recordSuccess(null, 1, 1);
        registry.recordFailure(null, "e", 1);

        assertThat(registry.snapshot()).isEmpty();
        assertThat(registry.get(null)).isNull();
        assertThat(registry.isCoolingDown(null, 1000)).isFalse();
        assertThat(registry.alerts()).isEmpty();
    }
}
