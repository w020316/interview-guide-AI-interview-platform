package com.example.interview.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RagHealthTracker} 单元测试（v1.34.1）
 *
 * <p>覆盖：播种状态流转、失败记录与清除、状态判定（UP / DEGRADED / DISABLED / UNKNOWN）与排查提示。
 */
@DisplayName("RAG 健康跟踪测试")
class RagHealthTrackerTest {

    private final RagHealthTracker tracker = new RagHealthTracker();

    @Test
    @DisplayName("初始状态：未播种 → UNKNOWN，且不含失败信息")
    void initialState_unknown() {
        Map<String, Object> snap = tracker.snapshot(0, 500);

        assertThat(snap.get("status")).isEqualTo("UNKNOWN");
        assertThat(snap.get("seedStatus")).isEqualTo("NOT_RUN");
        assertThat(snap.get("documents")).isEqualTo(0);
        assertThat(snap.get("maxDocuments")).isEqualTo(500);
        assertThat(snap).doesNotContainKey("lastFailure");
        assertThat(snap).doesNotContainKey("hint");
    }

    @Test
    @DisplayName("播种成功 → UP，含条数说明与时间")
    void seedSuccess_up() {
        tracker.markSeed(RagHealthTracker.SeedStatus.SUCCESS, "120 条预置知识已入库");

        Map<String, Object> snap = tracker.snapshot(120, 500);

        assertThat(snap.get("status")).isEqualTo("UP");
        assertThat(snap.get("seedStatus")).isEqualTo("SUCCESS");
        assertThat(snap.get("seedDetail")).isEqualTo("120 条预置知识已入库");
        assertThat(snap.get("seedAt")).isNotNull();
        assertThat(snap).doesNotContainKey("hint");
    }

    @Test
    @DisplayName("播种失败 → DEGRADED 并给出可执行排查提示（生产 Embedding 失效场景）")
    void seedFailed_degradedWithHint() {
        tracker.markSeed(RagHealthTracker.SeedStatus.FAILED, "播种失败：401 Invalid token");

        Map<String, Object> snap = tracker.snapshot(0, 500);

        assertThat(snap.get("status")).isEqualTo("DEGRADED");
        assertThat(snap.get("seedStatus")).isEqualTo("FAILED");
        assertThat(snap.get("seedDetail").toString()).contains("Invalid token");
        assertThat(snap.get("hint").toString())
                .contains("Embedding")
                .contains("AI_EMBEDDING_API_KEY");
    }

    @Test
    @DisplayName("显式关闭播种 → DISABLED，且不视为故障")
    void seedDisabled_disabled() {
        tracker.markSeed(RagHealthTracker.SeedStatus.DISABLED, "app.rag.seed-enabled=false");

        Map<String, Object> snap = tracker.snapshot(0, 500);

        assertThat(snap.get("status")).isEqualTo("DISABLED");
        assertThat(snap).doesNotContainKey("hint");
    }

    @Test
    @DisplayName("播种成功后出现向量化失败 → DEGRADED，并暴露失败原因与时间")
    void lateFailure_degrades() {
        tracker.markSeed(RagHealthTracker.SeedStatus.SUCCESS, "120 条已入库");

        tracker.markFailure("向量化入库失败：503 model_not_found");

        Map<String, Object> snap = tracker.snapshot(120, 500);
        assertThat(snap.get("status")).isEqualTo("DEGRADED");
        assertThat(snap.get("lastFailure").toString()).contains("model_not_found");
        assertThat(snap.get("lastFailureAt")).isNotNull();
        // 播种本身仍记录为成功，便于区分「启动即失败」与「运行期失败」
        assertThat(snap.get("seedStatus")).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("一次成功入库应清除历史失败，避免旧故障长期驻留误判")
    void clearFailure_restoresUp() {
        tracker.markSeed(RagHealthTracker.SeedStatus.SUCCESS, "ok");
        tracker.markFailure("临时失败");
        assertThat(tracker.snapshot(1, 500).get("status")).isEqualTo("DEGRADED");

        tracker.clearFailure();

        Map<String, Object> snap = tracker.snapshot(1, 500);
        assertThat(snap.get("status")).isEqualTo("UP");
        assertThat(snap).doesNotContainKey("lastFailure");
    }

    @Test
    @DisplayName("超长失败原因被截断，避免健康接口被大段错误文本撑爆")
    void longFailure_truncated() {
        tracker.markFailure("x".repeat(1000));

        assertThat(tracker.lastFailure().reason().length()).isLessThanOrEqualTo(300);
    }

    @Test
    @DisplayName("null 失败原因不抛异常")
    void nullFailure_isSafe() {
        tracker.markFailure(null);

        assertThat(tracker.lastFailure()).isNotNull();
        assertThat(tracker.lastFailure().reason()).isEqualTo("unknown");
    }
}
