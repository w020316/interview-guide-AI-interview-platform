package com.example.interview.service.task;

import com.example.interview.ai.AiGateTimeoutException;
import com.example.interview.common.BusinessException;
import com.example.interview.entity.AiTaskEntity;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 任务轮询调度器（阶段六 M2 骨架，M3 改为「调度只派发、执行走独立线程」）。
 *
 * <p>默认关闭（{@code app.task-center.enabled=false}）：关闭时既不领取也不执行，
 * 接口同时返回 503，前端据此回退到同步链路（见 {@code InterviewTaskController}）。
 *
 * <p>四个刻意的保守设计：
 * <ul>
 *   <li><b>没有执行器就不领取</b>：否则任务会被领成 RUNNING 然后卡住；</li>
 *   <li><b>同一时刻只跑一个任务</b>：{@code running} 标志从领取一直持有到执行结束，
 *       避免多个长耗时 AI 调用同时打满上游配额；</li>
 *   <li><b>执行不占用调度线程</b>：M2 时执行是同步的，一次出题 77s 会把整条
 *       {@code @Scheduled} 线程（默认池大小 1）堵住，岗位刷新等定时任务全部延后。
 *       M3 起改为领取后提交到独立线程，调度线程立即返回；</li>
 *   <li><b>异常一律不逃逸</b>：调度/执行线程抛出异常都会让后续轮次停摆，这里全部就地回写状态。</li>
 * </ul>
 */
@Component
public class AiTaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(AiTaskScheduler.class);

    /** 租约时长：覆盖出题 77s 的实测长尾，留出余量 */
    private static final int LEASE_SECONDS = 180;

    private final AiTaskService taskService;
    private final List<AiTaskHandler> handlers;

    /** 执行线程：单线程即可（同一时刻只跑一个任务），虚拟线程不必占平台线程 */
    private final Executor executor;
    /** 由本类自建的执行器（测试注入直执行器时为 null，不负责关闭） */
    private final ExecutorService ownedExecutor;

    @Value("${app.task-center.enabled:false}")
    private boolean enabled;

    /** 防重入 + 单任务闸门：上一轮尚未结束时跳过本轮 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 生产构造器：自建单线程执行器（测试用带 Executor 的包级构造器，故这里必须显式 @Autowired） */
    @org.springframework.beans.factory.annotation.Autowired
    public AiTaskScheduler(AiTaskService taskService, List<AiTaskHandler> handlers) {
        this(taskService, handlers, Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ai-task-runner");
            t.setDaemon(true);
            return t;
        }));
    }

    /** 测试用：注入直执行器（{@code Runnable::run}）即可恢复同步语义 */
    AiTaskScheduler(AiTaskService taskService, List<AiTaskHandler> handlers, Executor executor) {
        this.taskService = taskService;
        this.handlers = handlers;
        this.executor = executor;
        this.ownedExecutor = executor instanceof ExecutorService es ? es : null;
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
        Optional<AiTaskEntity> claimed;
        try {
            claimed = taskService.claimNext(LEASE_SECONDS);
        } catch (Exception e) {
            // 调度线程绝不能抛出：否则该定时任务后续轮次不再触发
            log.warn("任务领取异常（已吞掉，避免调度停摆）：{}", e.getMessage());
            running.set(false);
            return;
        }
        if (claimed.isEmpty()) {
            running.set(false);
            return;
        }
        AiTaskEntity task = claimed.get();
        try {
            executor.execute(() -> execute(task));
        } catch (Exception e) {
            // 线程池拒绝/关闭：任务留在 RUNNING，租约到期后会被重新领取
            log.warn("任务提交执行失败（租约到期后可重新领取） taskId={} err={}", task.getId(), e.getMessage());
            running.set(false);
        }
    }

    @PreDestroy
    void shutdown() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
        }
    }

    /**
     * 在执行线程上重建任务归属用户的 {@code SecurityContext} 后执行。
     *
     * <p>为什么必须重建：{@code SelfKeyAwareChatModel} 靠 {@code SecurityContext} 判断
     * 「当前用户是否配置了自持 AI Key」。调度线程没有登录态，若不重建，
     * 配了自己的 Key 的用户在异步链路里会被静默降级为平台共享 Key —— 功能能用但计费对象错了。
     *
     * @see com.example.interview.util.SecurityContextPropagation
     */
    private void execute(AiTaskEntity task) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                task.getUserId(), null, java.util.List.of()));
        com.example.interview.util.SecurityContextPropagation.runWith(context, () -> doExecute(task));
    }

    /** 真正的执行体；无论成败都必须释放 {@link #running} */
    private void doExecute(AiTaskEntity task) {
        try {
            Optional<AiTaskHandler> handler = handlers.stream()
                    .filter(h -> h.type().equals(task.getType()))
                    .findFirst();
            if (handler.isEmpty()) {
                // 有执行器注册但缺这一类：任务无法完成，直接判失败，避免占着租约反复重试
                log.error("任务类型缺少执行器 type={} taskId={}", task.getType(), task.getId());
                recordFailure(task, "NO_HANDLER", "该任务类型没有可用的执行器", false);
                return;
            }
            long start = System.currentTimeMillis();
            try {
                AiTaskOutcome outcome = handler.get().execute(task);
                AiTaskOutcome safe = outcome == null ? AiTaskOutcome.EMPTY : outcome;
                try {
                    taskService.markSucceededWithResult(task.getId(), safe.resultRef(), safe.resultText());
                    log.info("任务执行成功 taskId={} type={} cost={}ms hasText={}",
                            task.getId(), task.getType(), System.currentTimeMillis() - start,
                            safe.resultText() != null);
                } catch (BusinessException e) {
                    // 执行期间用户取消了任务：结果作废即可，不再回写状态（否则又是一次 409）
                    log.info("任务已结束（多半是用户已取消），忽略执行结果 taskId={} err={}",
                            task.getId(), e.getMessage());
                }
            } catch (BusinessException e) {
                // 业务异常按状态码判定：5xx（服务暂不可用）可重试；4xx（参数/冲突）不重试
                boolean retryable = e.getStatus() >= 500;
                recordFailure(task, "AI_UPSTREAM", e.getMessage(), retryable);
                log.warn("任务执行失败（可重试={}） taskId={} err={}", retryable, task.getId(), e.getMessage());
            } catch (AiGateTimeoutException e) {
                // 排队等不到 AI 许可 = 上游繁忙，属于可重试
                recordFailure(task, "AI_GATE_TIMEOUT", e.getMessage(), true);
                log.warn("任务执行失败（AI 闸门排队超时，可重试） taskId={} err={}", task.getId(), e.getMessage());
            } catch (Exception e) {
                // 未知异常保守按不可重试处理：宁可让用户看到失败，也不无限烧配额
                recordFailure(task, "UNKNOWN", e.getMessage(), false);
                log.error("任务执行异常 taskId={}", task.getId(), e);
            }
        } finally {
            running.set(false);
        }
    }

    /**
     * 回写失败态，但**不让状态冲突逃逸**。
     *
     * <p>用户可以在任务中心取消正在执行的任务（RUNNING → CANCELLED）。此时执行体仍会跑完，
     * 随后无论 markSucceeded 还是 markFailed 都会因「终态不可再流转」抛 409。
     * 若放任它逃逸，执行线程会打印一大段未捕获异常堆栈，而任务本身早已是用户期望的
     * CANCELLED —— 属于纯噪音，故这里降级为 debug。
     */
    private void recordFailure(AiTaskEntity task, String code, String message, boolean retryable) {
        try {
            taskService.markFailed(task.getId(), code, message, retryable);
        } catch (Exception e) {
            log.debug("任务状态回写被忽略（多半是用户已取消） taskId={} err={}", task.getId(), e.getMessage());
        }
    }
}
