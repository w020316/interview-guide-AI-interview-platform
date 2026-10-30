package com.example.interview.service.task;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AiTaskCleanupJob} 单元测试（阶段六 M4）。
 *
 * <p>两条语义：开关关闭时不动任何数据；清理异常不得逃逸（否则会打停其它定时任务）。
 */
@ExtendWith(MockitoExtension.class)
class AiTaskCleanupJobTest {

    @Mock
    private AiTaskService taskService;

    private AiTaskCleanupJob job(boolean enabled) {
        AiTaskCleanupJob j = new AiTaskCleanupJob(taskService);
        ReflectionTestUtils.setField(j, "enabled", enabled);
        return j;
    }

    @Test
    @DisplayName("开关关闭 → 不删任何任务（关闭异步链路不等于丢弃历史记录）")
    void disabled_doesNothing() {
        job(false).purge();
        verify(taskService, never()).purgeFinished(any());
    }

    @Test
    @DisplayName("开关打开 → 按当前时间清理")
    void enabled_purges() {
        when(taskService.purgeFinished(any(LocalDateTime.class))).thenReturn(6);

        job(true).purge();

        verify(taskService).purgeFinished(any(LocalDateTime.class));
    }

    @Test
    @DisplayName("清理抛异常 → 就地吞掉，不影响其它定时任务")
    void error_isSwallowed() {
        when(taskService.purgeFinished(any(LocalDateTime.class)))
                .thenThrow(new IllegalStateException("db down"));

        job(true).purge();
    }
}
