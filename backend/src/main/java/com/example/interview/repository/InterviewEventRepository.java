package com.example.interview.repository;

import com.example.interview.entity.InterviewEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

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
}