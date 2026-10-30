package com.example.interview.service.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 任务表清理（阶段六 M4）。
 *
 * <p>存在的理由：任务表是「按用户留存的操作日志」，成功任务只在完成通知里有价值，
 * 失败任务需要留够排障窗口。不清理的话，表会随使用量单调增长，且 summary / 列表查询变慢。
 *
 * <p>两个刻意的保守设计：
 * <ul>
 *   <li><b>跟随任务中心开关</b>：开关关闭时不删任何数据（用户可能只是临时关掉异步链路，
 *       不能顺手把历史记录清掉）；</li>
 *   <li><b>异常不逃逸</b>：清理失败只影响磁盘，绝不能影响其它定时任务。</li>
 * </ul>
 */
@Component
public class AiTaskCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(AiTaskCleanupJob.class);

    private final AiTaskService taskService;

    @Value("${app.task-center.enabled:false}")
    private boolean enabled;

    public AiTaskCleanupJob(AiTaskService taskService) {
        this.taskService = taskService;
    }

    /** 每日 03:00（本地时区）；只能在配置里改 cron，避免多人协作时改代码 */
    @Scheduled(cron = "${app.task-center.cleanup-cron:0 0 3 * * *}")
    public void purge() {
        if (!enabled) {
            return;
        }
        try {
            taskService.purgeFinished(LocalDateTime.now());
        } catch (Exception e) {
            log.warn("任务清理失败（已忽略，不影响其它定时任务）：{}", e.getMessage());
        }
    }
}
