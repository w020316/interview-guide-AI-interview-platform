package com.example.interview.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 面试日历事件实体
 * 对应数据库表 interview_event
 *
 * 记录求职面试/笔试等日程安排，按用户隔离。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "interview_event")
public class InterviewEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属用户 ID */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 事件标题（如公司 + 岗位） */
    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /** 面试官 / 联系人（可选） */
    @Column(name = "interviewer", length = 100)
    private String interviewer;

    /** 地点 / 线上链接（可选） */
    @Column(name = "location", length = 200)
    private String location;

    /** 备注（可选） */
    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    /** 面试时间 */
    @Column(name = "interview_at", nullable = false)
    private LocalDateTime interviewAt;

    /** 状态：UPCOMING / COMPLETED / CANCELLED */
    @Column(name = "status", length = 20)
    @Builder.Default
    private String status = "UPCOMING";

    /** 创建时间 */
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}