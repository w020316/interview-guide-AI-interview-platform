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
 * 岗位信息实体（招聘信息智能体）
 *
 * 数据来源：
 * - 内置秋招精选数据源（platform = 内置精选）
 * - 第三方招聘平台适配器（platform = 智联招聘/前程无忧/BOSS直聘，需配置数据服务）
 *
 * (platform, externalId) 联合唯一，刷新时按此键幂等 upsert。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "job_posting",
        uniqueConstraints = @UniqueConstraint(columnNames = {"platform", "external_id"}),
        indexes = {
                @Index(name = "idx_job_posting_deadline", columnList = "deadline"),
                @Index(name = "idx_job_posting_recruit_type", columnList = "recruit_type"),
                @Index(name = "idx_job_posting_active", columnList = "active")
        })
public class JobPostingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 数据来源平台：内置精选 / 智联招聘 / 前程无忧 / BOSS直聘 */
    @Column(name = "platform", nullable = false, length = 50)
    private String platform;

    /** 平台内唯一标识（用于幂等 upsert） */
    @Column(name = "external_id", nullable = false, length = 128)
    private String externalId;

    /** 岗位名称 */
    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /** 企业名称 */
    @Column(name = "company_name", nullable = false, length = 200)
    private String companyName;

    /** 行业分类：互联网/金融/制造/能源/教育/医疗/快消/通信/其他 */
    @Column(name = "industry", length = 50)
    private String industry;

    /** 职位类型：技术/产品/运营/设计/市场/职能/金融/其他 */
    @Column(name = "job_type", length = 50)
    private String jobType;

    /** 工作地点 */
    @Column(name = "location", length = 100)
    private String location;

    /** 薪资范围（展示文本） */
    @Column(name = "salary", length = 100)
    private String salary;

    /** 学历要求 */
    @Column(name = "degree", length = 50)
    private String degree;

    /** 经验要求 */
    @Column(name = "experience", length = 50)
    private String experience;

    /** 招聘类型：AUTUMN 秋招 / SPRING 春招 / SOCIAL 社招 / INTERN 实习 */
    @Column(name = "recruit_type", length = 20)
    private String recruitType;

    /** 申请截止日期 */
    @Column(name = "deadline")
    private LocalDate deadline;

    /** 申请入口 URL */
    @Column(name = "apply_url", length = 500)
    private String applyUrl;

    /** 岗位描述 */
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    /** 岗位要求（技能/任职要求） */
    @Column(name = "requirements", columnDefinition = "TEXT")
    private String requirements;

    /** 标签（逗号分隔，如 "校招,Java,五险一金"） */
    @Column(name = "tags", length = 500)
    private String tags;

    /** 是否有效（过期数据标记为 false，前端默认不展示） */
    @Builder.Default
    @Column(name = "active", nullable = false)
    private Boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
