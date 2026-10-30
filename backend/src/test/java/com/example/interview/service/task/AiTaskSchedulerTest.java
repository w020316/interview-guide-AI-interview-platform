package com.example.interview.service.task;

import com.example.interview.common.BusinessException;
import com.example.interview.entity.AiTaskEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AiTaskScheduler} 单元测试（阶段六 M2）。
 *
 * <p>核心不是「能跑通」，而是三条保守语义：关闭开关不领取、没有执行器不领取、
 * 执行失败必须回写状态（可重试 vs 终态）。
 */
@ExtendWith(MockitoExtension.class)
class AiTaskSchedulerTest {

    @Mock
    private AiTaskService taskService;

    private AiTaskEntity task(String type) {
        return AiTaskEntity.builder().id(7L).userId("u1").type(type)
                .idempotencyKey("k").status(AiTaskStatus.RUNNING.name()).attempt(1).maxAttempt(1).build();
    }

    private AiTaskScheduler scheduler(boolean enabled, List<AiTaskHandler> handlers) {
        AiTaskScheduler s = new AiTaskScheduler(taskService, handlers);
        ReflectionTestUtils.setField(s, "enabled", enabled);
        return s;
    }

    @Test
    @DisplayName("开关关闭 → 不领取任何任务（M2 默认状态，零行为变化）")
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
    @DisplayName("开关打开且有执行器 → 执行并写回结果引用")
    void executesAndSucceeds() {
        AiTaskHandler handler = new AiTaskHandler() {
            @Override
            public String type() {
                return "interview.questions";
            }

            @Override
            public String execute(AiTaskEntity t) {
                return "session-9";
            }
        };
        when(taskService.claimNext(anyInt())).thenReturn(Optional.of(task("interview.questions")));

        scheduler(true, List.of(handler)).poll();

        verify(taskService).markSucceeded(eq(7L), eq("session-9"));
    }

    @Test
    @DisplayName("类型没有对应执行器 → 记 NO_HANDLER 终态，不无限占用租约")
    void unknownType_failsFast() {
        AiTaskHandler handler = new AiTaskHandler() {
            @Override
            public String type() {
                return "resume.analyze";
            }

            @Override
            public String execute(AiTaskEntity t) {
                return null;
            }
        };
        when(taskService.claimNext(anyInt())).thenReturn(Optional.of(task("interview.questions")));

        scheduler(true, List.of(handler)).poll();

        verify(taskService).markFailed(eq(7L), eq("NO_HANDLER"), anyString(), eq(false));
        verify(taskService, never()).markSucceeded(any(), any());
    }

    @Test
    @DisplayName("业务异常 503 → 可重试；未知异常 → 不可重试")
    void failureClassification() {
        AiTaskHandler throwing = new AiTaskHandler() {
            @Override
            public String type() {
                return "interview.evaluate";
            }

            @Override
            public String execute(AiTaskEntity t) {
                throw new BusinessException("AI 服务暂时不可用");
            }
        };
        when(taskService.claimNext(anyInt())).thenReturn(Optional.of(task("interview.evaluate")));

        scheduler(true, List.of(throwing)).poll();

        verify(taskService).markFailed(eq(7L), eq("AI_UPSTREAM"), anyString(), eq(true));

        AiTaskHandler boom = new AiTaskHandler() {
            @Override
            public String type() {
                return "interview.evaluate";
            }

            @Override
            public String execute(AiTaskEntity t) {
                throw new IllegalStateException("boom");
            }
        };
        when(taskService.claimNext(anyInt())).thenReturn(Optional.of(task("interview.evaluate")));

        scheduler(true, List.of(boom)).poll();

        verify(taskService).markFailed(eq(7L), eq("UNKNOWN"), anyString(), eq(false));
    }

    @Test
    @DisplayName("队列为空 → 不写库、不报错")
    void emptyQueue() {
        AiTaskHandler handler = new AiTaskHandler() {
            @Override
            public String type() {
                return "interview.questions";
            }

            @Override
            public String execute(AiTaskEntity t) {
                return null;
            }
        };
        when(taskService.claimNext(anyInt())).thenReturn(Optional.empty());

        scheduler(true, List.of(handler)).poll();

        verify(taskService, never()).markSucceeded(any(), any());
        verify(taskService, never()).markFailed(any(), anyString(), anyString(), eq(true));
    }
}
