package com.example.interview.service.task;

import com.example.interview.common.BusinessException;
import com.example.interview.entity.AiTaskEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 任务轮询调度器（阶段六 M2）。
 *
 * <p>默认关闭（{@code app.task-center.enabled=false}）：M2 只提供接口与骨架，
 * M3 接入出题/评分执行器后才开启，避免「领到任务却无人执行」。
 *
 * <p>三个刻意的保守设计：
 * <ul>
 *   <li><b>没有执行器就不领取</b>：否则任务会被领成 RUNNING 然后卡住；</li>
 *   <li><b>单轮只处理一个任务</b>：当前调度线程是共享的，阻塞执行会挤占其它定时任务，
 *       M3 会改成「调度只派发、虚拟线程池执行」；</li>
 *   <li><b>异常一律不逃逸</b>：调度线程抛出异常会让后续轮次停摆，这里全部就地回写状态。</li>
 * </ul>
 */
@Component
public class AiTaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(AiTaskScheduler.class);

    /** 租约时长：覆盖出题 77s 的实测长尾，留出余量 */
    private static final int LEASE_SECONDS = 180;

    private final AiTaskService taskService;
    private final List<AiTaskHandler> handlers;

    @Value("${app.task-center.enabled:false}")
    private boolean enabled;

    /** 防重入：上一轮尚未结束时跳过本轮 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AiTaskScheduler(AiTaskService taskService, List<AiTaskHandler> handlers) {
        this.taskService = taskService;
        this.handlers = handlers;
    }

    @Scheduled(fixedDelayString = "${app.task-center.poll-interval-ms:5000}")
    public void poll() {
        if (!enabled || handlers.isEmpty()) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            log.debug("上一轮任务执行尚未结束，跳过本次轮询");
            return;
        }
        try {
            runOne();
        } catch (Exception e) {
            // 调度线程绝不能抛出：否则该定时任务后续轮次不再触发
            log.warn("任务轮询异常（已吞掉，避免调度停摆）：{}", e.getMessage());
        } finally {
            running.set(false);
        }
    }

    private void runOne() {
        Optional<AiTaskEntity> claimed = taskService.claimNext(LEASE_SECONDS);
        if (claimed.isEmpty()) {
            return;
        }
        AiTaskEntity task = claimed.get();
        Optional<AiTaskHandler> handler = handlers.stream()
                .filter(h -> h.type().equals(task.getType()))
                .findFirst();
        if (handler.isEmpty()) {
            // 有执行器注册但缺这一类：任务无法完成，直接判失败，避免占着租约反复重试
            log.error("任务类型缺少执行器 type={} taskId={}", task.getType(), task.getId());
            taskService.markFailed(task.getId(), "NO_HANDLER", "该任务类型没有可用的执行器", false);
            return;
        }
        long start = System.currentTimeMillis();
        try {
            String resultRef = handler.get().execute(task);
            taskService.markSucceeded(task.getId(), resultRef);
            log.info("任务执行成功 taskId={} type={} cost={}ms",
                    task.getId(), task.getType(), System.currentTimeMillis() - start);
        } catch (BusinessException e) {
            // 业务异常按状态码判定：5xx（服务暂不可用）可重试；4xx（参数/冲突）不重试
            boolean retryable = e.getStatus() >= 500;
            taskService.markFailed(task.getId(), "AI_UPSTREAM", e.getMessage(), retryable);
            log.warn("任务执行失败（可重试={}） taskId={} err={}", retryable, task.getId(), e.getMessage());
        } catch (Exception e) {
            // 未知异常保守按不可重试处理：宁可让用户看到失败，也不无限烧配额
            taskService.markFailed(task.getId(), "UNKNOWN", e.getMessage(), false);
            log.error("任务执行异常 taskId={}", task.getId(), e);
        }
    }
}
