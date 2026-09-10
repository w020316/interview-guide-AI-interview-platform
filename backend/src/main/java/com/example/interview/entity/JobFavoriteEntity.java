package com.example.interview.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 岗位收藏实体
 * 对应数据库表 job_favorite
 *
 * 收藏时对岗位做「快照」保存（名称/企业/截止日期等），
 * 即使原岗位过期下架（active=false）或被清理，收藏记录仍可回看并提示截止状态。
 * (user_id, job_id) 唯一，防止重复收藏。
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "job_favorite",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "job_id"}),
        indexes = @Index(name = "idx_job_favorite_user", columnList = "user_id"))
public class JobFavoriteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属用户 ID */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 岗位 ID（job_posting.id） */
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

    /** 申请截止日期（快照，站内提醒依据） */
    @Column(name = "deadline")
    private LocalDate deadline;

    /** 申请入口 URL（快照） */
    @Column(name = "apply_url", length = 500)
    private String applyUrl;

    /** 收藏时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
