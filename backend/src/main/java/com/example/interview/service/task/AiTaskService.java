package com.example.interview.service.task;

import com.example.interview.common.BusinessException;
import com.example.interview.common.ResourceNotFoundException;
import com.example.interview.entity.AiTaskEntity;
import com.example.interview.repository.AiTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * AI 任务生命周期服务（阶段六 M1）。
 *
 * <p>职责边界：只负责<b>状态与租约</b>，不执行 AI 调用。
 * 具体执行器（出题 / 评分 / 简历分析）在 M2 由调度器注入，M1 不接入任何业务链路，
 * 因此本类上线后对现有行为<b>零影响</b>。
 *
 * <p>三个容易写错的地方，都在这里集中处理：
 * <ul>
 *   <li><b>幂等</b>：同用户同键只复用「未失败」任务；FAILED 之后允许同键新建，
 *       否则用户重试会永远拿到那条失败记录；</li>
 *   <li><b>抢占</b>：领取走条件更新（见 {@link AiTaskRepository#claim}），
 *       受影响行数为 1 才算抢到，避免同一任务被执行两次；</li>
 *   <li><b>重试上限</b>：{@code attempt} 是「已开始次数」，失败时未到上限才回队列，
 *       到达上限转 FAILED，避免无限重试烧掉 AI 配额。</li>
 * </ul>
 */
@Service
public class AiTaskService {

    private static final Logger log = LoggerFactory.getLogger(AiTaskService.class);

    /** 允许的任务类型长度上限（与列宽一致） */
    static final int MAX_TYPE_LEN = 32;
    /** 幂等键长度上限（与列宽一致） */
    static final int MAX_KEY_LEN = 96;
    /** 单任务最大执行次数上限：防止配置失误造成长时间重试风暴 */
    static final int MAX_ATTEMPT_LIMIT = 5;
    /** 结果正文长度上限（M3）：出题 5–20 题的 JSON 实测 4–8KB，20K 留足余量且不让任务表膨胀 */
    static final int MAX_RESULT_TEXT_LEN = 20_000;
    /** 成功 / 取消任务的保留天数（M4）：只用于「完成通知」，7 天足够回溯 */
    static final int KEEP_SUCCEEDED_DAYS = 7;
    /** 失败任务的保留天数（M4）：失败要留够排障窗口，比成功多留 3 周 */
    static final int KEEP_FAILED_DAYS = 30;

    private final AiTaskRepository repository;

    public AiTaskService(AiTaskRepository repository) {
        this.repository = repository;
    }

    // ── 创建 ────────────────────────────────────────────────────────────

    public AiTaskEntity create(String userId, String type, String idempotencyKey, String payload, int maxAttempt) {
        return create(userId, type, idempotencyKey, payload, maxAttempt, LocalDateTime.now());
    }

    /**
     * 创建任务（幂等）。
     *
     * @param maxAttempt 含首次的最大执行次数（1–5）
     * @return 新建任务，或同键下「未失败」的既有任务
     */
    @Transactional
    public AiTaskEntity create(String userId, String type, String idempotencyKey,
                               String payload, int maxAttempt, LocalDateTime now) {
        requireText(userId, "userId", 64);
        requireText(type, "type", MAX_TYPE_LEN);
        requireText(idempotencyKey, "idempotencyKey", MAX_KEY_LEN);
        if (maxAttempt < 1 || maxAttempt > MAX_ATTEMPT_LIMIT) {
            throw new IllegalArgumentException("maxAttempt 必须在 1–" + MAX_ATTEMPT_LIMIT + " 之间");
        }
        Optional<AiTaskEntity> existing =
                repository.findFirstByUserIdAndIdempotencyKeyAndStatusIn(userId, idempotencyKey, AiTaskStatus.ACTIVE);
        if (existing.isPresent()) {
            log.debug("复用既有任务 userId={} type={} id={} status={}",
                    userId, type, existing.get().getId(), existing.get().getStatus());
            return existing.get();
        }
        AiTaskEntity task = AiTaskEntity.builder()
                .userId(userId)
                .type(type)
                .idempotencyKey(idempotencyKey)
                .status(AiTaskStatus.QUEUED.name())
                .progress(0)
                .attempt(0)
                .maxAttempt(maxAttempt)
                .payload(payload)
                .createdAt(now)
                .build();
        return repository.save(task);
    }

    // ── 领取 ────────────────────────────────────────────────────────────

    /**
     * 领取一个任务（便捷入口，调度器用这个）。
     *
     * <p>⚠️ 必须与下面的重载一样标 {@code @Transactional}：本方法内部是<b>自调用</b>
     * （{@code claimNext(now, lease)}），绕过了 Spring 代理，二参重载上的事务注解不会生效。
     * 而 {@code AiTaskRepository#claim} 是 {@code @Modifying} 查询，没有事务时 Hibernate 直接抛
     * 「Executing an update/delete query」—— 表现是任务永远停在 QUEUED
     * （2026-10-30 本地启动实测发现；单测走的是二参重载，从未覆盖到这个入口）。
     */
    @Transactional
    public Optional<AiTaskEntity> claimNext(int leaseSeconds) {
        return claimNext(LocalDateTime.now(), leaseSeconds);
    }

    /**
     * 领取一个任务并置为 RUNNING：优先 QUEUED，其次「租约已过期」的 RUNNING。
     *
     * @param leaseSeconds 租约时长；进程崩溃后租约过期，任务在下一轮可被重新领取
     * @return 抢到的任务；队列为空或竞争失败时为空
     */
    @Transactional
    public Optional<AiTaskEntity> claimNext(LocalDateTime now, int leaseSeconds) {
        if (leaseSeconds <= 0) {
            throw new IllegalArgumentException("leaseSeconds 必须为正数");
        }
        Optional<AiTaskEntity> candidate =
                repository.findFirstByStatusOrderByCreatedAtAsc(AiTaskStatus.QUEUED.name());
        if (candidate.isPresent()) {
            return tryClaim(candidate.get(), now, leaseSeconds);
        }
        // M4：队列空时再看「进程崩溃遗留的 RUNNING」。M1 注释承诺了租约可重领，
        // 但那时 claim 只认 QUEUED —— 崩过一次的任务会永远停在「执行中」。
        Optional<AiTaskEntity> stale = repository.findFirstByStatusAndLeaseUntilBeforeOrderByCreatedAtAsc(
                AiTaskStatus.RUNNING.name(), now);
        if (stale.isEmpty()) {
            return Optional.empty();
        }
        AiTaskEntity task = stale.get();
        int attempt = task.getAttempt() == null ? 0 : task.getAttempt();
        int maxAttempt = task.getMaxAttempt() == null ? 1 : task.getMaxAttempt();
        if (attempt >= maxAttempt) {
            // 已用完重试次数仍停在 RUNNING：直接判终态，否则它会一直占着「执行中」不能结束
            task.setStatus(AiTaskStatus.FAILED.name());
            task.setErrorCode("LEASE_EXPIRED");
            task.setErrorMessage("执行中断且已达重试上限，请重新发起");
            task.setFinishedAt(now);
            task.setLeaseUntil(null);
            repository.save(task);
            log.warn("租约过期的任务已达重试上限，转 FAILED taskId={} attempt={}", task.getId(), attempt);
            return Optional.empty();
        }
        int won = repository.reclaim(task.getId(), now.plusSeconds(leaseSeconds), now);
        if (won != 1) {
            log.debug("过期任务重领竞争失败 id={} won={}", task.getId(), won);
            return Optional.empty();
        }
        log.warn("重领租约过期的任务（上次执行可能已崩溃） taskId={} attempt={}", task.getId(), attempt + 1);
        return repository.findById(task.getId());
    }

    private Optional<AiTaskEntity> tryClaim(AiTaskEntity candidate, LocalDateTime now, int leaseSeconds) {
        Long id = candidate.getId();
        int won = repository.claim(id, now.plusSeconds(leaseSeconds), now);
        if (won != 1) {
            // 竞争失败：已被其它执行器领取或用户取消，交给下一轮调度
            log.debug("任务领取竞争失败 id={} won={}", id, won);
            return Optional.empty();
        }
        return repository.findById(id);
    }

    // ── 清理（M4）────────────────────────────────────────────────────────

    /**
     * 清理已结束且超过保留期的任务。
     *
     * <p>保留期：成功 / 取消 7 天，失败 30 天（失败要留够排障窗口，成功只是通知用）。
     * 未结束的任务（QUEUED / RUNNING）{@code finishedAt} 为空，天然不会被删。
     *
     * @return 删除行数（用于日志与测试判据）
     */
    @Transactional
    public int purgeFinished(LocalDateTime now) {
        LocalDateTime successBefore = now.minusDays(KEEP_SUCCEEDED_DAYS);
        LocalDateTime failedBefore = now.minusDays(KEEP_FAILED_DAYS);
        int removed = repository.deleteFinishedBefore(AiTaskStatus.SUCCEEDED.name(), successBefore)
                + repository.deleteFinishedBefore(AiTaskStatus.CANCELLED.name(), successBefore)
                + repository.deleteFinishedBefore(AiTaskStatus.FAILED.name(), failedBefore);
        if (removed > 0) {
            log.info("任务清理完成 removed={}（成功/取消保留 {} 天，失败保留 {} 天）",
                    removed, KEEP_SUCCEEDED_DAYS, KEEP_FAILED_DAYS);
        }
        return removed;
    }

    // ── 结束 ────────────────────────────────────────────────────────────

    /** 执行成功：写入结果引用，进度置 100，释放租约 */
    @Transactional
    public AiTaskEntity markSucceeded(Long id, String resultRef) {
        return markSucceeded(id, resultRef, LocalDateTime.now());
    }

    /**
     * 执行成功（M3）：同时写入结果正文。
     *
     * <p>单独命名而非重载 {@link #markSucceeded(Long, String, LocalDateTime)}，
     * 避免 `markSucceeded(1L, "x", null)` 在 String / LocalDateTime 之间产生歧义。
     *
     * @param resultText 结果正文（出题/评分）；超出 {@link #MAX_RESULT_TEXT_LEN} 直接判失败，
     *                   不静默截断——截断后的 JSON 在前端只会「解析失败」，
     *                   错误现场反而更难定位
     */
    @Transactional
    public AiTaskEntity markSucceededWithResult(Long id, String resultRef, String resultText) {
        if (resultText != null && resultText.length() > MAX_RESULT_TEXT_LEN) {
            throw new IllegalArgumentException(
                    "任务结果超出上限（" + MAX_RESULT_TEXT_LEN + " 字符），请减少单次生成数量");
        }
        AiTaskEntity task = requireTask(id);
        requireTransit(task, AiTaskStatus.SUCCEEDED);
        task.setStatus(AiTaskStatus.SUCCEEDED.name());
        task.setProgress(100);
        task.setResultRef(resultRef);
        task.setResultText(resultText);
        task.setErrorCode(null);
        task.setErrorMessage(null);
        task.setFinishedAt(LocalDateTime.now());
        task.setLeaseUntil(null);
        return repository.save(task);
    }

    @Transactional
    public AiTaskEntity markSucceeded(Long id, String resultRef, LocalDateTime now) {
        AiTaskEntity task = requireTask(id);
        requireTransit(task, AiTaskStatus.SUCCEEDED);
        task.setStatus(AiTaskStatus.SUCCEEDED.name());
        task.setProgress(100);
        task.setResultRef(resultRef);
        task.setErrorCode(null);
        task.setErrorMessage(null);
        task.setFinishedAt(now);
        task.setLeaseUntil(null);
        return repository.save(task);
    }

    /**
     * 执行失败。
     *
     * @param retryable 是否为可重试故障（上游 429 / 超时 / 空响应等）；
     *                  不可重试（配置错误 1211、鉴权失败）直接 FAILED，避免白烧配额
     */
    @Transactional
    public AiTaskEntity markFailed(Long id, String errorCode, String errorMessage, boolean retryable) {
        return markFailed(id, errorCode, errorMessage, retryable, LocalDateTime.now());
    }

    @Transactional
    public AiTaskEntity markFailed(Long id, String errorCode, String errorMessage,
                                   boolean retryable, LocalDateTime now) {
        AiTaskEntity task = requireTask(id);
        requireTransit(task, AiTaskStatus.FAILED);
        int attempt = task.getAttempt() == null ? 0 : task.getAttempt();
        int maxAttempt = task.getMaxAttempt() == null ? 1 : task.getMaxAttempt();
        boolean canRetry = retryable && attempt < maxAttempt;
        task.setErrorCode(errorCode);
        task.setErrorMessage(clip(errorMessage, 200));
        task.setLeaseUntil(null);
        if (canRetry) {
            // 回到队列：attempt 保持不变，下一次领取时会再 +1
            task.setStatus(AiTaskStatus.QUEUED.name());
            task.setProgress(0);
        } else {
            task.setStatus(AiTaskStatus.FAILED.name());
            task.setFinishedAt(now);
        }
        return repository.save(task);
    }

    // ── 取消 ────────────────────────────────────────────────────────────

    /**
     * 用户取消（仅本人、仅未结束的任务）。
     *
     * <p>已取消的任务重复取消返回当前态（幂等）；已成功/已失败返回 409，
     * 避免「结果已产出但记录被改成取消」造成数据与展示不一致。
     */
    @Transactional
    public AiTaskEntity cancel(String userId, Long id) {
        return cancel(userId, id, LocalDateTime.now());
    }

    @Transactional
    public AiTaskEntity cancel(String userId, Long id, LocalDateTime now) {
        requireText(userId, "userId", 64);
        AiTaskEntity task = repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("任务不存在或无权操作"));
        if (AiTaskStatus.CANCELLED.name().equals(task.getStatus())) {
            return task;
        }
        requireTransit(task, AiTaskStatus.CANCELLED);
        task.setStatus(AiTaskStatus.CANCELLED.name());
        task.setFinishedAt(now);
        task.setLeaseUntil(null);
        return repository.save(task);
    }

    // ── 查询（M2）────────────────────────────────────────────────────────

    /** 归属校验后取任务；非本人一律 404（不暴露任务是否存在） */
    @Transactional(readOnly = true)
    public AiTaskEntity findOwned(String userId, Long id) {
        requireText(userId, "userId", 64);
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("任务不存在或无权操作"));
    }

    /** 我的任务分页列表；status 为空表示全部 */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<AiTaskEntity> listOwned(String userId, String status,
                                                                       int page, int size) {
        requireText(userId, "userId", 64);
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 50);
        org.springframework.data.domain.Pageable pageable =
                org.springframework.data.domain.PageRequest.of(safePage, safeSize);
        if (status == null || status.isBlank()) {
            return repository.findByUserIdOrderByCreatedAtDesc(userId, pageable);
        }
        return repository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, status.toUpperCase(), pageable);
    }

    /** 未完成/失败计数（导航角标与「完成提醒」数据源） */
    @Transactional(readOnly = true)
    public java.util.Map<String, Long> summary(String userId) {
        requireText(userId, "userId", 64);
        return java.util.Map.of(
                "queued", repository.countByUserIdAndStatus(userId, AiTaskStatus.QUEUED.name()),
                "running", repository.countByUserIdAndStatus(userId, AiTaskStatus.RUNNING.name()),
                "failed", repository.countByUserIdAndStatus(userId, AiTaskStatus.FAILED.name()));
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private AiTaskEntity requireTask(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("任务不存在"));
    }

    private void requireTransit(AiTaskEntity task, AiTaskStatus target) {
        if (!AiTaskStatus.canTransit(task.getStatus(), target.name())) {
            throw new BusinessException(409,
                    "任务状态不允许该操作（当前 " + task.getStatus() + " → " + target.name() + "）");
        }
    }

    private static void requireText(String value, String name, int maxLen) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        if (value.length() > maxLen) {
            throw new IllegalArgumentException(name + " 超长（最多 " + maxLen + " 字符）");
        }
    }

    private static String clip(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }
}
