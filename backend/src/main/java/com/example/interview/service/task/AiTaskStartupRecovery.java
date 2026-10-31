package com.example.interview.service.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 启动时收敛「上次进程留下的在途任务」（P1-1，2026-10-31）。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>任务中心的回滚动作是「改环境变量 + 重启」，而 {@link AiTaskScheduler} 在
 * {@code @PreDestroy} 里调 {@code shutdownNow()} —— 在途任务是被**硬杀**的，
 * 状态会永远停在 RUNNING。与此同时，关闭开关时 {@code poll()} 直接 return，
 * {@code claimNext} 的「租约过期重领」分支永远不会被调用，清理任务也只删终态行，
 * 于是没有任何代码会回收它们：用户看到「进行中」，却既等不到结果也点不了取消。
 *
 * <p>收敛策略见 {@link AiTaskService#recoverOrphansOnStartup(boolean, LocalDateTime)}：
 * 开关开 → 有重试余量的退回队列、用完的判失败；开关关 → 一律判失败并给出「去重新发起」。
 *
 * <h2>为什么吞掉异常</h2>
 *
 * <p>这是启动钩子：抛出去会连累整个应用启动失败，而任务收敛本身是「补救」性质，
 * 失败最多退回原来的租约机制兜底，绝不该让服务起不来。
 */
@Component
public class AiTaskStartupRecovery {

    private static final Logger log = LoggerFactory.getLogger(AiTaskStartupRecovery.class);

    private final AiTaskService taskService;

    @Value("${app.task-center.enabled:false}")
    private boolean enabled;

    public AiTaskStartupRecovery(AiTaskService taskService) {
        this.taskService = taskService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        try {
            taskService.recoverOrphansOnStartup(enabled, LocalDateTime.now());
        } catch (Exception e) {
            // 收敛失败不影响启动：退回租约机制兜底，但必须留痕
            log.warn("启动收敛失败（不影响启动，交由租约机制兜底）：{}", e.getMessage());
        }
    }
}
