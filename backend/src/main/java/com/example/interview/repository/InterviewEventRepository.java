package com.example.interview.repository;

import com.example.interview.entity.InterviewEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 面试日历事件 JPA Repository
 */
@Repository
public interface InterviewEventRepository extends JpaRepository<InterviewEventEntity, Long> {

    /** 查询用户全部日程，按面试时间升序（最近的在前） */
    List<InterviewEventEntity> findByUserIdOrderByInterviewAtAsc(String userId);

    /** 查询某用户指定时间范围内的日程 */
    List<InterviewEventEntity> findByUserIdAndInterviewAtBetween(
            String userId, LocalDateTime from, LocalDateTime to);

    /**
     * 查「同标题 + 同时刻」是否已存在（第三批收口 · 创建去重）。
     *
     * <p>放在仓库层而不是「先查列表再在内存里比」，是为了让判定与写库在同一次调用链里，
     * 并且可被单测精确桩住。命中即由 service 抛 409（不静默去重）。
     */
    Optional<InterviewEventEntity> findFirstByUserIdAndTitleAndInterviewAt(
            String userId, String title, LocalDateTime interviewAt);
}