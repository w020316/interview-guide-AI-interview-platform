package com.example.interview.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 投递台账实体（v1.35.0，源自「求职 agent 大更新 / BossHunter」的投递闭环思路）
 *
 * <p>对应数据库表 {@code job_application}。设计要点：
 * <ul>
 *   <li><b>人工确认投递</b>：平台只负责「本地台账 + 定制简历 + 状态提醒」，
 *       从不代替用户登录招聘平台或自动投递（平台规则与账号安全风险，明确不做）；</li>
 *   <li><b>快照式存储</b>：岗位标题/企业/链接在加入台账时做快照，
 *       即使原岗位下架也能回看「我投过什么」；</li>
 *   <li><b>回复监测</b>：{@link #status} 状态机 + {@link #lastReplyAt} / {@link #nextActionAt}
 *       支撑「谁回了、谁该跟进」的看板视图，无需后台定时任务（read-time 计算）。</li>
 * </ul>
 *
 * (user_id, job_id) 联合唯一，同一岗位只允许存在一条投递记录（重复加入即幂等返回）。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "job_application",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "job_id"}),
        indexes = {
                @Index(name = "idx_job_application_user", columnList = "user_id"),
                @Index(name = "idx_job_application_status", columnList = "status")
        })
public class JobApplicationEntity {

    /** 待投递：已加入台账但尚未真正投出 */
    public static final String STATUS_PLANNED = "PLANNED";
    /** 已投递 */
    public static final String STATUS_APPLIED = "APPLIED";
    /** 简历已被查看 */
    public static final String STATUS_VIEWED = "VIEWED";
    /** 已收到回复/沟通中 */
    public static final String STATUS_REPLIED = "REPLIED";
    /** 进入面试环节 */
    public static final String STATUS_INTERVIEW = "INTERVIEW";
    /** 已拿 Offer */
    public static final String STATUS_OFFER = "OFFER";
    /** 已淘汰/被拒 */
    public static final String STATUS_REJECTED = "REJECTED";
    /** 主动放弃 */
    public static final String STATUS_WITHDRAWN = "WITHDRAWN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属用户 ID（JWT subject） */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 岗位 ID（job_posting.id）；来自自定义来源时为 0 */
    @Column(name = "job_id", nullable = false)
    private Long jobId;

    /** 岗位名称（快照） */
    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /** 企业名称（快照） */
    @Column(name = "company_name", nullable = false, length = 200)
    private String companyName;

    /** 数据来源平台（快照） */
    @Column(name = "platform", length = 50)
    private String platform;

    /** 工作地点（快照） */
    @Column(name = "location", length = 100)
    private String location;

    /** 薪资范围（快照） */
    @Column(name = "salary", length = 100)
    private String salary;

    /** 申请截止日期（快照） */
    @Column(name = "deadline")
    private LocalDate deadline;

    /** 申请入口 URL（快照，由用户自行前往投递） */
    @Column(name = "apply_url", length = 500)
    private String applyUrl;

    /** 投递状态：见 STATUS_* 常量 */
    @Builder.Default
    @Column(name = "status", nullable = false, length = 20)
    private String status = STATUS_PLANNED;

    /** 针对该岗位生成的定制简历要点（AI 产出，JSON 文本；可为空） */
    @Column(name = "tailored_resume", columnDefinition = "TEXT")
    private String tailoredResume;

    /** 用户备注（联系人、渠道、面试反馈等） */
    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    /** 实际投出时间 */
    @Column(name = "applied_at")
    private LocalDateTime appliedAt;

    /** 最近一次收到回复/状态变化的时间（回复监测依据） */
    @Column(name = "last_reply_at")
    private LocalDateTime lastReplyAt;

    /** 下次跟进时间（看板「待跟进」提醒依据） */
    @Column(name = "next_action_at")
    private LocalDateTime nextActionAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
