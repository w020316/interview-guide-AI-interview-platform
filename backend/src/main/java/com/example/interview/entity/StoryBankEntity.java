package com.example.interview.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 面试故事库实体（v1.36.0，源自「VibeCoding大赏｜求职Skill」的 interview-story-bank 思路）
 *
 * <p>对应数据库表 {@code story_bank}。核心洞察：面试不是背答案，是经得起追问——
 * 而经得起追问的前提，是把每一段真实经历按 STAR 结构预先整理成「讲得清、有证据」的故事。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>用户资产持久化</b>：职业资产挖掘（CareerProfileService）是即时应答，
 *       本表把「打磨好的故事」沉淀为可反复练习的个人资产；</li>
 *   <li><b>不编造</b>：故事由 AI 从用户自述/简历中提炼，提示词严格要求不得新增
 *       未提及的经历、数字与奖项；</li>
 *   <li><b>练习闭环</b>：{@link #checkResult} 保存最近一次「六项质检」结果
 *       （结构清楚/证据具体/贴合岗位/无废话/无风险表达/经得起追问）。</li>
 * </ul>
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "story_bank", indexes = {
        @Index(name = "idx_story_bank_user", columnList = "user_id")
})
public class StoryBankEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属用户 ID（JWT subject） */
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    /** 故事标题（一句话概括这段经历，如「二手交易平台性能优化」） */
    @Column(name = "title", nullable = false, length = 200)
    private String title;

    /** STAR - S：情境（项目/任务背景） */
    @Column(name = "situation", columnDefinition = "TEXT")
    private String situation;

    /** STAR - T：任务（你的目标与职责） */
    @Column(name = "task", columnDefinition = "TEXT")
    private String task;

    /** STAR - A：行动（具体做了什么，怎么取舍的） */
    @Column(name = "action", columnDefinition = "TEXT")
    private String action;

    /** STAR - R：结果（量化结果/被谁认可） */
    @Column(name = "result", columnDefinition = "TEXT")
    private String result;

    /** 证据链摘要（证据→行为→能力→岗位信号的第一层：可验证的客观事实） */
    @Column(name = "evidence", columnDefinition = "TEXT")
    private String evidence;

    /** 能力标签（逗号分隔，如「需求理解,性能优化,快速学习」） */
    @Column(name = "capability_tags", length = 500)
    private String capabilityTags;

    /** 期望赛道/岗位方向（可选，用于质检时判断「是否贴合岗位」） */
    @Column(name = "target_track", length = 200)
    private String targetTrack;

    /** 最近一次六项质检结果（AI 产出 JSON 文本；可为空） */
    @Column(name = "check_result", columnDefinition = "TEXT")
    private String checkResult;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
