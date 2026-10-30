package com.example.interview.service.task;

import com.example.interview.common.BusinessException;
import com.example.interview.common.ResourceNotFoundException;
import com.example.interview.entity.AiTaskEntity;
import com.example.interview.repository.AiTaskRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiTaskService} 单元测试（阶段六 M1）。
 *
 * <p>锁定的都是「改一处就会静默出错」的语义：
 * 幂等复用范围、抢占竞争失败、可重试失败回队列、终态不可再流转、越权取消。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AiTaskService 任务生命周期")
class AiTaskServiceTest {

    @Mock
    private AiTaskRepository repository;

    @InjectMocks
    private AiTaskService service;

    private static final String USER = "user-1";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 30, 21, 0);

    private AiTaskEntity task(String status, int attempt, int maxAttempt) {
        return AiTaskEntity.builder()
                .id(1L)
                .userId(USER)
                .type("interview.questions")
                .idempotencyKey("key-1")
                .status(status)
                .attempt(attempt)
                .maxAttempt(maxAttempt)
                .build();
    }

    // ── 创建 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("同键已有未失败任务 → 复用，不重复入库（防重复计费）")
    void create_reusesActiveTask() {
        AiTaskEntity exist = task(AiTaskStatus.RUNNING.name(), 1, 1);
        when(repository.findFirstByUserIdAndIdempotencyKeyAndStatusIn(eq(USER), eq("key-1"), anyCollection()))
                .thenReturn(Optional.of(exist));

        AiTaskEntity result = service.create(USER, "interview.questions", "key-1", "{}", 1, NOW);

        assertThat(result).isSameAs(exist);
        verify(repository, never()).save(any(AiTaskEntity.class));
    }

    @Test
    @DisplayName("同键只有失败记录 → 允许新建（否则用户重试永远拿到失败记录）")
    void create_allowsNewAfterFailure() {
        when(repository.findFirstByUserIdAndIdempotencyKeyAndStatusIn(eq(USER), eq("key-1"), anyCollection()))
                .thenReturn(Optional.empty());
        when(repository.save(any(AiTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        AiTaskEntity result = service.create(USER, "interview.questions", "key-1", "{}", 2, NOW);

        assertThat(result.getStatus()).isEqualTo(AiTaskStatus.QUEUED.name());
        assertThat(result.getAttempt()).isZero();
        assertThat(result.getMaxAttempt()).isEqualTo(2);
        assertThat(result.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("幂等键为空 → 400 语义（IllegalArgumentException）")
    void create_rejectsBlankKey() {
        assertThatThrownBy(() -> service.create(USER, "interview.questions", " ", "{}", 1, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyKey");
    }

    @Test
    @DisplayName("maxAttempt 越界 → 拒绝，避免无限重试风暴")
    void create_rejectsOutOfRangeMaxAttempt() {
        assertThatThrownBy(() -> service.create(USER, "t", "k", "{}", 0, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(USER, "t", "k", "{}", 6, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── 领取 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("队列为空 → 不领取、不写库")
    void claim_emptyQueue() {
        when(repository.findFirstByStatusOrderByCreatedAtAsc(AiTaskStatus.QUEUED.name()))
                .thenReturn(Optional.empty());

        assertThat(service.claimNext(NOW, 60)).isEmpty();
        verify(repository, never()).claim(any(), any(), any());
    }

    @Test
    @DisplayName("条件更新命中 → 重新读取 RUNNING 任务；租约按时长写入")
    void claim_success() {
        when(repository.findFirstByStatusOrderByCreatedAtAsc(AiTaskStatus.QUEUED.name()))
                .thenReturn(Optional.of(task(AiTaskStatus.QUEUED.name(), 0, 1)));
        when(repository.claim(eq(1L), eq(NOW.plusSeconds(120)), eq(NOW))).thenReturn(1);
        AiTaskEntity running = task(AiTaskStatus.RUNNING.name(), 1, 1);
        when(repository.findById(1L)).thenReturn(Optional.of(running));

        Optional<AiTaskEntity> got = service.claimNext(NOW, 120);

        assertThat(got).containsSame(running);
        assertThat(running.getStatus()).isEqualTo(AiTaskStatus.RUNNING.name());
    }

    @Test
    @DisplayName("竞争失败（条件更新影响 0 行）→ 返回空，交给下一轮，不重复执行")
    void claim_lostRace() {
        when(repository.findFirstByStatusOrderByCreatedAtAsc(AiTaskStatus.QUEUED.name()))
                .thenReturn(Optional.of(task(AiTaskStatus.QUEUED.name(), 0, 1)));
        when(repository.claim(any(), any(), any())).thenReturn(0);

        assertThat(service.claimNext(NOW, 60)).isEmpty();
        verify(repository, never()).findById(any());
    }

    @Test
    @DisplayName("非法租约时长 → 拒绝")
    void claim_rejectsNonPositiveLease() {
        assertThatThrownBy(() -> service.claimNext(NOW, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── 结束 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("RUNNING → SUCCEEDED：进度 100、结果引用写入、租约释放")
    void succeed_fromRunning() {
        AiTaskEntity running = task(AiTaskStatus.RUNNING.name(), 1, 1);
        when(repository.findById(1L)).thenReturn(Optional.of(running));
        when(repository.save(any(AiTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        AiTaskEntity done = service.markSucceeded(1L, "session-9", NOW);

        assertThat(done.getStatus()).isEqualTo(AiTaskStatus.SUCCEEDED.name());
        assertThat(done.getProgress()).isEqualTo(100);
        assertThat(done.getResultRef()).isEqualTo("session-9");
        assertThat(done.getLeaseUntil()).isNull();
        assertThat(done.getFinishedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("QUEUED → SUCCEEDED 属非法流转 → 409（防止未执行就成功）")
    void succeed_fromQueued_conflict() {
        when(repository.findById(1L)).thenReturn(Optional.of(task(AiTaskStatus.QUEUED.name(), 0, 1)));

        assertThatThrownBy(() -> service.markSucceeded(1L, "x", NOW))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(409));
        verify(repository, never()).save(any(AiTaskEntity.class));
    }

    @Test
    @DisplayName("M3：成功时写入结果正文，进度与终态同 M1 语义")
    void succeedWithResult_writesText() {
        AiTaskEntity running = task(AiTaskStatus.RUNNING.name(), 1, 1);
        when(repository.findById(1L)).thenReturn(Optional.of(running));
        when(repository.save(any(AiTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        AiTaskEntity done = service.markSucceededWithResult(1L, null, "[{\"question\":\"q\"}]");

        assertThat(done.getStatus()).isEqualTo(AiTaskStatus.SUCCEEDED.name());
        assertThat(done.getProgress()).isEqualTo(100);
        assertThat(done.getResultText()).isEqualTo("[{\"question\":\"q\"}]");
        assertThat(done.getLeaseUntil()).isNull();
        assertThat(done.getFinishedAt()).isNotNull();
    }

    @Test
    @DisplayName("M3：结果正文超上限 → 拒绝（不静默截断成坏 JSON）")
    void succeedWithResult_tooLong() {
        String tooLong = "x".repeat(AiTaskService.MAX_RESULT_TEXT_LEN + 1);

        assertThatThrownBy(() -> service.markSucceededWithResult(1L, null, tooLong))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("任务结果超出上限");
        verify(repository, never()).save(any(AiTaskEntity.class));
    }

    @Test
    @DisplayName("可重试失败且未到上限 → 回 QUEUED 并保留 attempt")
    void fail_retryable_requeues() {
        AiTaskEntity running = task(AiTaskStatus.RUNNING.name(), 1, 2);
        when(repository.findById(1L)).thenReturn(Optional.of(running));
        when(repository.save(any(AiTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        AiTaskEntity after = service.markFailed(1L, "UPSTREAM_429", "上游限流", true, NOW);

        assertThat(after.getStatus()).isEqualTo(AiTaskStatus.QUEUED.name());
        assertThat(after.getAttempt()).isEqualTo(1);
        assertThat(after.getLeaseUntil()).isNull();
        assertThat(after.getErrorMessage()).isEqualTo("上游限流");
    }

    @Test
    @DisplayName("已达上限或不可重试 → FAILED 终态（不再烧配额）")
    void fail_terminal() {
        AiTaskEntity running = task(AiTaskStatus.RUNNING.name(), 2, 2);
        when(repository.findById(1L)).thenReturn(Optional.of(running));
        when(repository.save(any(AiTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        AiTaskEntity after = service.markFailed(1L, "CONFIG_1211", "模型不存在", true, NOW);

        assertThat(after.getStatus()).isEqualTo(AiTaskStatus.FAILED.name());
        assertThat(after.getFinishedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("不可重试故障 → 即使未到上限也直接 FAILED")
    void fail_nonRetryable() {
        AiTaskEntity running = task(AiTaskStatus.RUNNING.name(), 1, 3);
        when(repository.findById(1L)).thenReturn(Optional.of(running));
        when(repository.save(any(AiTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        AiTaskEntity after = service.markFailed(1L, "AUTH_401", "鉴权失败", false, NOW);

        assertThat(after.getStatus()).isEqualTo(AiTaskStatus.FAILED.name());
    }

    // ── 取消 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("取消他人任务 → 404（不暴露任务是否存在）")
    void cancel_otherUser_notFound() {
        when(repository.findByIdAndUserId(1L, "user-2")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancel("user-2", 1L, NOW))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("无权");
    }

    @Test
    @DisplayName("RUNNING → CANCELLED 成功；重复取消幂等返回当前态")
    void cancel_running_thenIdempotent() {
        AiTaskEntity running = task(AiTaskStatus.RUNNING.name(), 1, 1);
        when(repository.findByIdAndUserId(1L, USER)).thenReturn(Optional.of(running));
        when(repository.save(any(AiTaskEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        AiTaskEntity cancelled = service.cancel(USER, 1L, NOW);
        assertThat(cancelled.getStatus()).isEqualTo(AiTaskStatus.CANCELLED.name());

        AiTaskEntity again = service.cancel(USER, 1L, NOW);
        assertThat(again.getStatus()).isEqualTo(AiTaskStatus.CANCELLED.name());
    }

    @Test
    @DisplayName("已成功 → 取消被拒（409），避免结果与状态不一致")
    void cancel_succeeded_conflict() {
        when(repository.findByIdAndUserId(1L, USER))
                .thenReturn(Optional.of(task(AiTaskStatus.SUCCEEDED.name(), 1, 1)));

        assertThatThrownBy(() -> service.cancel(USER, 1L, NOW))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getStatus()).isEqualTo(409));
    }

    // ── 状态机 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("状态机：终态不可再流转，未知状态一律拒绝")
    void stateMachine() {
        assertThat(AiTaskStatus.canTransit("QUEUED", "RUNNING")).isTrue();
        assertThat(AiTaskStatus.canTransit("RUNNING", "QUEUED")).isTrue();
        assertThat(AiTaskStatus.canTransit("FAILED", "QUEUED")).isTrue();
        assertThat(AiTaskStatus.canTransit("SUCCEEDED", "QUEUED")).isFalse();
        assertThat(AiTaskStatus.canTransit("CANCELLED", "RUNNING")).isFalse();
        assertThat(AiTaskStatus.canTransit("QUEUED", "SUCCEEDED")).isFalse();
        assertThat(AiTaskStatus.canTransit("BOGUS", "QUEUED")).isFalse();
        assertThat(AiTaskStatus.isTerminal("SUCCEEDED")).isTrue();
        assertThat(AiTaskStatus.isTerminal("QUEUED")).isFalse();
    }
}
