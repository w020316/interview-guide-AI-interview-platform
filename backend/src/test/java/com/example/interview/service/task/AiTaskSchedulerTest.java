package com.example.interview.service.task;

import com.example.interview.ai.AiGateTimeoutException;
import com.example.interview.common.BusinessException;
import com.example.interview.entity.AiTaskEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AiTaskScheduler} 单元测试（阶段六 M2 骨架 + M3 改造）。
 *
 * <p>核心不是「能跑通」，而是几条保守语义：关闭开关不领取、没有执行器不领取、
 * 执行失败必须回写状态（可重试 vs 终态）、执行不占调度线程、执行线程要带上任务归属用户。
 *
 * <p>测试注入 {@code Runnable::run} 作为执行器，把 M3 的异步派发压回同步语义，
 * 从而可以像 M2 一样直接断言结果 —— 这是**测试替身**，不是放宽断言。
 */
@ExtendWith(MockitoExtension.class)
class AiTaskSchedulerTest {

    private static final Executor DIRECT = Runnable::run;

    @Mock
    private AiTaskService taskService;

    private AiTaskEntity task(String type) {
        return AiTaskEntity.builder().id(7L).userId("u1").type(type)
                .idempotencyKey("k").status(AiTaskStatus.RUNNING.name()).attempt(1).maxAttempt(1).build();
    }

    private AiTaskScheduler scheduler(boolean enabled, List<AiTaskHandler> handlers) {
        AiTaskScheduler s = new AiTaskScheduler(taskService, handlers, DIRECT);
        ReflectionTestUtils.setField(s, "enabled", enabled);
        return s;
    }

    private static AiTaskHandler handler(String type, java.util.function.Supplier<AiTaskOutcome> body) {
        return new AiTaskHandler() {
            @Override
            public String type() {
                return type;
            }

            @Override
            public AiTaskOutcome execute(AiTaskEntity t) {
                return body.get();
            }
        };
    }

    @Test
    @DisplayName("执行期间任务被用户取消 → 结果作废，不再回写状态（也不打未捕获异常）")
    void cancelledDuringRun_ignoresResult() {
        when(taskService.claimNext(anyInt())).thenReturn(Optional.of(task(AiTaskTypes.INTERVIEW_QUESTIONS)));
        org.mockito.Mockito.doThrow(new BusinessException(409, "任务状态不允许该操作（当前 CANCELLED → SUCCEEDED）"))
                .when(taskService).markSucceededWithResult(any(), any(), any());

        scheduler(true, List.of(handler(AiTaskTypes.INTERVIEW_QUESTIONS,
                () -> AiTaskOutcome.ofText("[]")))).poll();

        verify(taskService, never()).markFailed(any(), anyString(), anyString(), eq(false));
    }

    @Test
    @DisplayName("开关关闭 → 不领取任何任务（默认状态，零行为变化）")
    void disabled_doesNothing() {
        scheduler(false, List.of()).poll();
        verifyNoInteractions(taskService);
    }

    @Test
    @DisplayName("开关打开但没有执行器 → 不领取（否则任务会被领成 RUNNING 卡死）")
    void noHandlers_doesNothing() {
        scheduler(true, List.of()).poll();
        verifyNoInteractions(taskService);
    }

    @Test
    @DisplayName("执行成功 → 写回结果引用；有正文时一并落库")
    void executesAndSucceeds() {
        when(taskService.claimNext(anyInt())).thenReturn(Optional.of(task(AiTaskTypes.INTERVIEW_QUESTIONS)));

        scheduler(true, List.of(handler(AiTaskTypes.INTERVIEW_QUESTIONS,
                () -> new AiTaskOutcome("session-9", "[]")))).poll();

        verify(taskService).markSucceededWithResult(eq(7L), eq("session-9"), eq("[]"));
    }

    @Test
    @DisplayName("执行器返回 null → 按空结果处理，不能因此判失败")
    void nullOutcomeTreatedAsEmpty() {
        when(taskService.claimNext(anyInt())).thenReturn(Optional.of(task(AiTaskTypes.INTERVIEW_QUESTIONS)));

        scheduler(true, List.of(handler(AiTaskTypes.INTERVIEW_QUESTIONS, () -> null))).poll();

        verify(taskService).markSucceededWithResult(eq(7L), eq(null), eq(null));
    }

    @Test
    @DisplayName("类型没有对应执行器 → 记 NO_HANDLER 终态，不无限占用租约")
    void unknownType_failsFast() {
        when(taskService.claimNext(anyInt())).thenReturn(Optional.of(task(AiTaskTypes.INTERVIEW_QUESTIONS)));

        scheduler(true, List.of(handler("resume.analyze", () -> AiTaskOutcome.EMPTY))).poll();

        verify(taskService).markFailed(eq(7L), eq("NO_HANDLER"), anyString(), eq(false));
        verify(taskService, never()).markSucceededWithResult(any(), any(), any());
    }

    @Test
    @DisplayName("业务异常 5xx → 可重试；4xx → 不可重试；未知异常 → 不可重试")
    void failureClassification() {
        when(taskService.claimNext(anyInt())).thenReturn(Optional.of(task(AiTaskTypes.INTERVIEW_EVALUATE)));
        scheduler(true, List.of(handler(AiTaskTypes.INTERVIEW_EVALUATE, () -> {
            throw new BusinessException("AI 服务暂时不可用");
        }))).poll();
        verify(taskService).markFailed(eq(7L), eq("AI_UPSTREAM"), anyString(), eq(true));

        when(taskService.claimNext(anyInt())).thenReturn(Optional.of(task(AiTaskTypes.INTERVIEW_EVALUATE)));
        scheduler(true, List.of(handler(AiTaskTypes.INTERVIEW_EVALUATE, () -> {
            throw new BusinessException(400, "任务参数格式错误");
        }))).poll();
        verify(taskService).markFailed(eq(7L), eq("AI_UPSTREAM"), anyString(), eq(false));

        when(taskService.claimNext(anyInt())).thenReturn(Optional.of(task(AiTaskTypes.INTERVIEW_EVALUATE)));
        scheduler(true, List.of(handler(AiTaskTypes.INTERVIEW_EVALUATE, () -> {
            throw new IllegalStateException("boom");
        }))).poll();
        verify(taskService).markFailed(eq(7L), eq("UNKNOWN"), anyString(), eq(false));
    }

    @Test
    @DisplayName("AI 闸门排队超时 → 可重试（属于上游繁忙，不是配置错误）")
    void gateTimeoutIsRetryable() {
        when(taskService.claimNext(anyInt())).thenReturn(Optional.of(task(AiTaskTypes.INTERVIEW_QUESTIONS)));
        scheduler(true, List.of(handler(AiTaskTypes.INTERVIEW_QUESTIONS, () -> {
            throw new AiGateTimeoutException("排队超时");
        }))).poll();

        verify(taskService).markFailed(eq(7L), eq("AI_GATE_TIMEOUT"), anyString(), eq(true));
    }

    @Test
    @DisplayName("队列为空 → 不写库、不报错")
    void emptyQueue() {
        when(taskService.claimNext(anyInt())).thenReturn(Optional.empty());
        scheduler(true, List.of(handler(AiTaskTypes.INTERVIEW_QUESTIONS,
                () -> AiTaskOutcome.EMPTY))).poll();

        verify(taskService, never()).markSucceededWithResult(any(), any(), any());
        verify(taskService, never()).markFailed(any(), anyString(), anyString(), eq(true));
    }

    @Test
    @DisplayName("调度只派发：执行被提交到独立执行器，且执行未完成时本轮不再领取")
    void singleFlight() {
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        when(taskService.claimNext(anyInt())).thenReturn(Optional.of(task(AiTaskTypes.INTERVIEW_QUESTIONS)));
        AiTaskScheduler s = new AiTaskScheduler(taskService,
                List.of(handler(AiTaskTypes.INTERVIEW_QUESTIONS, () -> AiTaskOutcome.ofText("[]"))), queued::add);
        ReflectionTestUtils.setField(s, "enabled", true);

        s.poll();
        // 执行体还没跑，闸门被占用 → 第二次轮询不得再领取（避免长耗时 AI 并发打满上游）
        s.poll();

        verify(taskService, org.mockito.Mockito.times(1)).claimNext(anyInt());
        assertThat(queued).hasSize(1);

        queued.get(0).run();
        verify(taskService).markSucceededWithResult(eq(7L), eq(null), eq("[]"));

        // 执行结束后闸门释放，下一轮可以继续领取
        s.poll();
        verify(taskService, org.mockito.Mockito.times(2)).claimNext(anyInt());
    }

    @Test
    @DisplayName("执行线程内重建任务归属用户的 SecurityContext（自持 Key 才不会静默降级）")
    void propagatesSecurityContext() {
        when(taskService.claimNext(anyInt())).thenReturn(Optional.of(task(AiTaskTypes.INTERVIEW_QUESTIONS)));
        final String[] seen = new String[1];
        scheduler(true, List.of(handler(AiTaskTypes.INTERVIEW_QUESTIONS, () -> {
            seen[0] = String.valueOf(SecurityContextHolder.getContext().getAuthentication().getPrincipal());
            return AiTaskOutcome.EMPTY;
        }))).poll();

        assertThat(seen[0]).isEqualTo("u1");
    }
}
