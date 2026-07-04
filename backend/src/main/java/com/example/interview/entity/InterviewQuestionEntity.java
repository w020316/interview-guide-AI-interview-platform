package com.example.interview.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * 面试题目实体
 * 对应数据库表 interview_question
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "interview_question")
public class InterviewQuestionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属会话 ID */
    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    /** 面试题目 */
    @Column(name = "question", nullable = false, columnDefinition = "TEXT")
    private String question;

    /** 题目分类（项目深挖、Java 基础等） */
    @Column(name = "category", length = 50)
    private String category;

    /** 难度：EASY / MEDIUM / HARD */
    @Column(name = "difficulty", length = 20)
    private String difficulty;

    /** 考察知识点（逗号分隔） */
    @Column(name = "key_points", columnDefinition = "TEXT")
    private String keyPoints;

    /** 参考答案 */
    @Column(name = "reference_answer", columnDefinition = "TEXT")
    private String referenceAnswer;

    /** 用户回答 */
    @Column(name = "user_answer", columnDefinition = "TEXT")
    private String userAnswer;

    /** AI 评分（0-100） */
    @Column(name = "evaluation_score")
    private Integer evaluationScore;

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
