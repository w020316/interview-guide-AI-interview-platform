package com.example.interview.repository;

import com.example.interview.entity.AiTaskEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AiTaskRepository} 启动收敛查询的真实 SQL 测试（P1-1，2026-10-31）。
 *
 * <h2>为什么必须起 JPA 而不是 mock</h2>
 *
 * <p>这三条查询是回滚链路的兜底：如果 JPQL 写错（字段名、比较方向、状态集合），
 * 编译期发现不了，单元测试用 mock 也发现不了 —— 表现是「启动日志说收敛完成，
 * 但库里 RUNNING 一条没少」，也就是这次 P1 的原样复现。
 *
 * <p>锁定的语义：
 * <ol>
 *   <li>{@code attempt < maxAttempt} 才退回队列（否则会与「次数耗尽判失败」抢同一批行）；</li>
 *   <li>次数耗尽的 RUNNING 必须落到 FAILED 并写 {@code finished_at}（否则清理任务永远删不掉它）；</li>
 *   <li>回滚收敛只碰未结束的行，已成功的任务必须保持可读（用户还要取结果）。</li>
 * </ol>
 */
@DataJpaTest
@ActiveProfiles("local")
@DisplayName("AiTaskRepository 启动收敛（P1-1）")
class AiTaskRepositoryRecoveryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 31, 9, 0);

    @Autowired
    private AiTaskRepository repository;

    private AiTaskEntity save(String key, String status, int attempt, int maxAttempt) {
        return repository.saveAndFlush(AiTaskEntity.builder()
                .userId("u1")
                .type("interview.questions")
                .idempotencyKey(key)
                .status(status)
                .attempt(attempt)
                .maxAttempt(maxAttempt)
                .progress(40)
                .leaseUntil(NOW.minusMinutes(5))
                .build());
    }

    @Test
    @DisplayName("退回队列：只动「还有重试余量」的 RUNNING，QUEUED/SUCCEEDED 不受影响")
    void requeue_onlyMovesRunningWithBudget() {
        AiTaskEntity withBudget = save("k1", "RUNNING", 1, 2);
        AiTaskEntity exhausted = save("k2", "RUNNING", 2, 2);
        AiTaskEntity queued = save("k3", "QUEUED", 0, 2);
        AiTaskEntity succeeded = save("k4", "SUCCEEDED", 1, 2);

        assertThat(repository.requeueOrphanRunning()).isEqualTo(1);

        AiTaskEntity back = repository.findById(withBudget.getId()).orElseThrow();
        assertThat(back.getStatus()).isEqualTo("QUEUED");
        assertThat(back.getLeaseUntil()).as("退回队列必须释放租约，否则下一轮领取会把它当过期行").isNull();
        assertThat(back.getProgress()).isZero();
        assertThat(back.getAttempt()).as("attempt 不能被收敛重置，否则重试上限形同虚设").isEqualTo(1);

        assertThat(repository.findById(exhausted.getId()).orElseThrow().getStatus())
                .as("次数耗尽的留给 failExhaustedRunning 处理").isEqualTo("RUNNING");
        assertThat(repository.findById(queued.getId()).orElseThrow().getStatus()).isEqualTo("QUEUED");
        assertThat(repository.findById(succeeded.getId()).orElseThrow().getStatus()).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("次数耗尽：RUNNING 落 FAILED 并写 finished_at（否则清理任务永远删不掉）")
    void failExhausted_onlyAtLimit() {
        AiTaskEntity exhausted = save("k5", "RUNNING", 2, 2);
        AiTaskEntity withBudget = save("k6", "RUNNING", 1, 2);

        assertThat(repository.failExhaustedRunning("执行中断且已达重试上限，请重新发起", NOW)).isEqualTo(1);

        AiTaskEntity failed = repository.findById(exhausted.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        assertThat(failed.getErrorCode()).isEqualTo("LEASE_EXPIRED");
        assertThat(failed.getFinishedAt()).as("终态必须有结束时间").isNotNull();
        assertThat(failed.getLeaseUntil()).isNull();
        assertThat(failed.getProgress()).isZero();

        assertThat(repository.findById(withBudget.getId()).orElseThrow().getStatus())
                .as("还有余量的不能被这条查询判死").isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("回滚收敛：RUNNING/QUEUED 一律判失败，已成功/已取消/已失败保持原样")
    void rollback_failsUnfinishedAndKeepsFinished() {
        AiTaskEntity running = save("k7", "RUNNING", 1, 2);
        AiTaskEntity queued = save("k8", "QUEUED", 0, 2);
        AiTaskEntity succeeded = save("k9", "SUCCEEDED", 1, 1);
        AiTaskEntity cancelled = save("k10", "CANCELLED", 1, 1);
        AiTaskEntity failed = save("k11", "FAILED", 1, 1);

        assertThat(repository.failUnfinishedForRollback("任务中心已回滚，请重新发起", NOW)).isEqualTo(2);

        for (AiTaskEntity row : new AiTaskEntity[]{running, queued}) {
            AiTaskEntity reloaded = repository.findById(row.getId()).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo("FAILED");
            assertThat(reloaded.getErrorCode()).isEqualTo("ROLLBACK");
            assertThat(reloaded.getErrorMessage()).isEqualTo("任务中心已回滚，请重新发起");
            assertThat(reloaded.getFinishedAt()).isNotNull();
            assertThat(reloaded.getLeaseUntil()).isNull();
            assertThat(reloaded.getProgress()).isZero();
        }

        assertThat(repository.findById(succeeded.getId()).orElseThrow().getStatus())
                .as("已成功的任务不能被回滚收敛改坏：用户还要取结果").isEqualTo("SUCCEEDED");
        assertThat(repository.findById(cancelled.getId()).orElseThrow().getStatus()).isEqualTo("CANCELLED");
        assertThat(repository.findById(failed.getId()).orElseThrow().getStatus()).isEqualTo("FAILED");
    }
}
