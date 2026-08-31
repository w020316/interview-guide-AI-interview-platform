package com.example.interview.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 错题收藏实体
 * 对应数据库表 favorite_question
 *
 * 收藏时对题目做「快照」保存，便于在收藏夹集中回看，且不依赖原会话题目是否仍存在。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Entity
@Table(name = "favorite_question")
public class FavoriteQuestionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属用户 ID */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 来源面试会话 ID（冗余，便于追溯） */
    @Column(name = "session_id", length = 64)
    private String sessionId;

    /** 原题目 ID（可为 null，若原题已被清理） */
    @Column(name = "question_id")
    private Long questionId;

    /** 题目内容（快照） */
    @Column(name = "question", nullable = false, columnDefinition = "TEXT")
    private String question;

    /** 题目分类（快照） */
    @Column(name = "category", length = 50)
    private String category;

    /** 难度 EASY/MEDIUM/HARD（快照） */
    @Column(name = "difficulty", length = 20)
    private String difficulty;

    /** 参考答案（快照） */
    @Column(name = "reference_answer", columnDefinition = "TEXT")
    private String referenceAnswer;

    /** 我的回答（快照） */
    @Column(name = "user_answer", columnDefinition = "TEXT")
    private String userAnswer;

    /** 评估得分（快照） */
    @Column(name = "evaluation_score")
    private Integer evaluationScore;

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