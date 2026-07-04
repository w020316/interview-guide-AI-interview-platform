package com.example.interview.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 面试会话实体
 * 对应数据库表 interview_session
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "interview_session")
public class InterviewSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 会话唯一标识（业务 ID） */
    @Column(name = "session_id", nullable = false, unique = true, length = 64)
    private String sessionId;

    /** 用户 ID */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 关联简历 ID */
    @Column(name = "resume_id")
    private Long resumeId;

    /** 目标岗位描述 */
    @Column(name = "job_description", columnDefinition = "TEXT")
    private String jobDescription;

    /** 会话状态：ONGOING / FINISHED / CANCELLED */
    @Column(name = "status", length = 20)
    @Builder.Default
    private String status = "ONGOING";

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
