package com.example.interview.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 简历实体
 * 对应数据库表 resume
 * 存储用户上传/粘贴的简历内容与 AI 分析结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "resume")
public class ResumeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 用户 ID（来自 JWT subject） */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 简历文本内容 */
    @Column(name = "content", columnDefinition = "TEXT")
    private String content;

    /** 文件 URL（若来自 Supabase Storage） */
    @Column(name = "file_url", length = 512)
    private String fileUrl;

    /** 目标岗位 */
    @Column(name = "target_job", length = 200)
    private String targetJob;

    /** 综合评分（0-100） */
    @Column(name = "overall_score")
    private Integer overallScore;

    /** AI 分析结果 JSON */
    @Column(name = "analysis_result", columnDefinition = "JSONB")
    private String analysisResult;

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
