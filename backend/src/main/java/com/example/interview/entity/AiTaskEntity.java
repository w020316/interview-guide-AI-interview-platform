package com.example.interview.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI 异步任务实体（阶段六 M1，2026-10-30）。
 *
 * <p>对应表 {@code ai_task}。设计动机见 {@code docs/quality-loop-2026-10-30/06-调研与立项.md}：
 * 出题实测 77s、评分 11–29s，用户无法离开页面；目标是把长耗时 AI 调用从请求线程剥离，
 * 变为「可查询、可重试、可取消」的后台任务。
 *
 * <p><b>为什么不用外部队列</b>：免费层是单实例、且无常驻 worker，broker 的收益无法兑现。
 * 任务量级（单人单次面试 3–5 个）远低于队列系统的必要阈值，故用「任务表 + 单实例调度」。
 *
 * <p><b>本类不存大字段</b>：只存 {@code resultRef}（结果引用，如会话/简历 ID），
 * 正文仍写各自业务表 —— 避免任务表膨胀，也避免同一份内容存两处产生不一致。
 *
 * <p>状态：QUEUED → RUNNING → SUCCEEDED / FAILED；QUEUED/RUNNING → CANCELLED。
 * 状态常量定义在 {@code service.task.AiTaskStatus}，本类只存字符串（与 {@link InterviewSessionEntity} 同一写法）。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ai_task", indexes = {
        @Index(name = "idx_ai_task_user", columnList = "user_id"),
        @Index(name = "idx_ai_task_status", columnList = "status")
})
public class AiTaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属用户（JWT subject，VARCHAR(64) 与其它表一致） */
    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    /** 任务类型：interview.questions / interview.evaluate / resume.analyze …（≤32） */
    @Column(name = "type", length = 32, nullable = false)
    private String type;

    /**
     * 幂等键（≤96）。同用户同键仅允许一条「未失败」任务：
     * 用户连点或网络重试时复用原任务，避免重复计费与重复写库。
     */
    @Column(name = "idempotency_key", length = 96, nullable = false)
    private String idempotencyKey;

    /** 状态：QUEUED / RUNNING / SUCCEEDED / FAILED / CANCELLED */
    @Column(name = "status", length = 16, nullable = false)
    @Builder.Default
    private String status = "QUEUED";

    /** 进度百分比（0–100），仅用于展示，不参与状态判定 */
    @Column(name = "progress")
    @Builder.Default
    private Integer progress = 0;

    /** 已开始执行次数（领取一次 +1），用于重试上限判定 */
    @Column(name = "attempt", nullable = false)
    @Builder.Default
    private Integer attempt = 0;

    /** 最大执行次数（含首次）；1 表示不重试 */
    @Column(name = "max_attempt", nullable = false)
    @Builder.Default
    private Integer maxAttempt = 1;

    /** 入参快照（脱敏后）。只存必要参数，不存大文本 */
    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    /** 结果引用：会话 ID / 简历 ID 等，不存正文 */
    @Column(name = "result_ref", length = 64)
    private String resultRef;

    /**
     * 结果正文（M3 新增，可空）。
     *
     * <p><b>为什么还是存了正文</b>：出题/评分的结果（题目 JSON、评分 JSON）原本只回给前端、
     * 不落任何业务表；异步化之后用户可能已经离开页面，结果必须能在他回来时取回，
     * 因此没有「业务表」可以挂 {@code resultRef}。
     *
     * <p>与「任务表不存大文本」的边界：只允许 {@code interview.questions} /
     * {@code interview.evaluate} 这两类写入，且服务层有硬上限（见 {@code AiTaskService#saveResultText}）；
     * 简历正文、会话记录等大文本仍然只走各自的业务表。
     *
     * <p>{@code @JsonIgnore}：列表接口一次最多返回 50 条，若带上正文会把响应撑到 MB 级。
     * 正文单独由 {@code GET /api/tasks/{id}/result} 取。
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @lombok.ToString.Exclude
    @Column(name = "result_text", columnDefinition = "TEXT")
    private String resultText;

    /** 失败原因码（机器可读，如 UPSTREAM_429 / CONFIG_1211 / UNKNOWN） */
    @Column(name = "error_code", length = 32)
    private String errorCode;

    /** 失败原因（面向用户，≤200，禁止透传堆栈） */
    @Column(name = "error_message", length = 200)
    private String errorMessage;

    /**
     * 抢占租约到期时间。
     *
     * <p>执行器领取任务时写入；进程崩溃后租约不再续期，下一轮调度可重新领取，
     * 避免「进程死了任务永远 RUNNING」。续租阈值按任务类型分档（出题 180s / 评分 120s）。
     */
    @Column(name = "lease_until")
    private LocalDateTime leaseUntil;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
