package com.example.interview.repository;

import com.example.interview.entity.AiTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;

/**
 * AI 任务仓储（阶段六 M1）。
 *
 * <p>关键点：领取任务用**条件更新**而不是「查到再改」——
 * {@link #claim} 把「仅当仍为 QUEUED 时才置 RUNNING」写进 SQL，
 * 受影响行数为 1 才算抢到。这样即便未来多实例，也不会两个进程同时执行同一任务。
 */
@Repository
public interface AiTaskRepository extends JpaRepository<AiTaskEntity, Long> {

    /** 幂等键去重：只在「未失败」状态下复用（FAILED 之后允许同键新建） */
    Optional<AiTaskEntity> findFirstByUserIdAndIdempotencyKeyAndStatusIn(
            String userId, String idempotencyKey, Collection<String> statuses);

    /** 领取队首任务（按创建时间升序，先到先跑） */
    Optional<AiTaskEntity> findFirstByStatusOrderByCreatedAtAsc(String status);

    /**
     * 租约已过期、仍处于 RUNNING 的任务（M4：进程崩溃/被杀的遗留）。
     *
     * <p>M1 注释里承诺「租约到期可被重新领取」，但 {@link #claim} 只认 QUEUED，
     * 这条查询才把这个承诺落实：先找 QUEUED，没有再看有没有过期的 RUNNING。
     */
    Optional<AiTaskEntity> findFirstByStatusAndLeaseUntilBeforeOrderByCreatedAtAsc(
            String status, LocalDateTime now);

    /**
     * 条件领取：仅当任务仍处于 QUEUED 时置为 RUNNING，并写入租约与开始时间。
     *
     * @return 1=抢到，0=已被其它执行器领取/已被取消
     */
    @Modifying(clearAutomatically = true)
    @Query("update AiTaskEntity t set t.status = 'RUNNING', t.attempt = t.attempt + 1, "
            + "t.leaseUntil = :leaseUntil, t.startedAt = :startedAt "
            + "where t.id = :id and t.status = 'QUEUED'")
    int claim(@Param("id") Long id,
              @Param("leaseUntil") LocalDateTime leaseUntil,
              @Param("startedAt") LocalDateTime startedAt);

    /** 归属校验后取任务（取消/查询用，防 IDOR） */
    Optional<AiTaskEntity> findByIdAndUserId(Long id, String userId);

    /**
     * 重新领取「租约过期」的任务（M4）。
     *
     * <p>条件更新与 {@link #claim} 同构：只有「仍是 RUNNING 且租约确实过期」才抢到，
     * 避免把另一个执行器正在跑的任务抢走。attempt 同样 +1，受 max_attempt 约束。
     */
    @Modifying(clearAutomatically = true)
    @Query("update AiTaskEntity t set t.leaseUntil = :leaseUntil, t.startedAt = :startedAt, "
            + "t.attempt = t.attempt + 1 "
            + "where t.id = :id and t.status = 'RUNNING' and t.leaseUntil < :startedAt")
    int reclaim(@Param("id") Long id,
                @Param("leaseUntil") LocalDateTime leaseUntil,
                @Param("startedAt") LocalDateTime startedAt);

    /**
     * 清理终态任务（M4）。
     *
     * <p>只删「已结束且早于保留期」的行：终态任务的 {@code finishedAt} 非空，
     * 未结束的（QUEUED / RUNNING）永远不会命中，避免把正在排队的任务删掉。
     */
    @Modifying(clearAutomatically = true)
    @Query("delete from AiTaskEntity t where t.status = :status and t.finishedAt < :before")
    int deleteFinishedBefore(@Param("status") String status, @Param("before") LocalDateTime before);

    /** 我的任务列表（分页，最近创建在前） */
    org.springframework.data.domain.Page<AiTaskEntity> findByUserIdOrderByCreatedAtDesc(
            String userId, org.springframework.data.domain.Pageable pageable);

    /** 我的任务列表（按状态过滤） */
    org.springframework.data.domain.Page<AiTaskEntity> findByUserIdAndStatusOrderByCreatedAtDesc(
            String userId, String status, org.springframework.data.domain.Pageable pageable);

    /** 某状态下我的任务数（summary 角标用） */
    long countByUserIdAndStatus(String userId, String status);
}
