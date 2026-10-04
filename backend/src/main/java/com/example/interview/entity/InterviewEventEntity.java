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

    /**
     * 关联的投递记录 ID（{@code job_application.id}）。
     *
     * <p>v1.61.0 新增，用于投递 ↔ 面试时序视图（竞品清单 #4）。语义要点：
     * <ul>
     *   <li><b>可为 {@code null}</b>：用户在日历里手工新建的日程（如笔试、宣讲会）
     *       本就不属于任何一次投递，硬塞一个关联就是编造；</li>
     *   <li><b>不存外键约束</b>：台账记录被移除（清除追踪）后，这里保留的 id 会成为悬空引用，
     *       读取侧按 {@code null} 处理（见 {@code ApplicationTimelineService.resolveLinked}），
     *       不会因此报错或显示错乱的关联对象；</li>
     *   <li>由「投递通知识别」写入日历时自动带上（见 {@code ApplicationView.pushCalendarEvent}）。</li>
     * </ul>
     */
    @Column(name = "application_id")
    private Long applicationId;

    /** 面试时间 */
    @Column(name = "interview_at", nullable = false)
    private LocalDateTime interviewAt;

    /**
     * 状态：UPCOMING / DONE / CANCELLED。
     * 取值白名单与前端 {@code CalendarView.vue} 的 option、{@code utils/calendar.ts} 的
     * {@code EventStatus} 保持一致（第六轮 P1-04 校正：此前注释误写 COMPLETED，实际前端用 DONE）。
     */
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