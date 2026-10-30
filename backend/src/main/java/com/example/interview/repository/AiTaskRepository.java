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

    /** 我的任务列表（分页，最近创建在前） */
    org.springframework.data.domain.Page<AiTaskEntity> findByUserIdOrderByCreatedAtDesc(
            String userId, org.springframework.data.domain.Pageable pageable);

    /** 我的任务列表（按状态过滤） */
    org.springframework.data.domain.Page<AiTaskEntity> findByUserIdAndStatusOrderByCreatedAtDesc(
            String userId, String status, org.springframework.data.domain.Pageable pageable);

    /** 某状态下我的任务数（summary 角标用） */
    long countByUserIdAndStatus(String userId, String status);
}
