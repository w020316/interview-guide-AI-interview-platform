package com.example.interview.repository;

import com.example.interview.entity.InterviewSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 面试会话 JPA Repository
 */
@Repository
public interface InterviewSessionRepository extends JpaRepository<InterviewSessionEntity, Long> {

    /** 通过业务 sessionId 查找会话 */
    Optional<InterviewSessionEntity> findBySessionId(String sessionId);

    /** 查询指定用户的所有会话，按创建时间倒序 */
    List<InterviewSessionEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    /** 判断 sessionId 是否已存在 */
    boolean existsBySessionId(String sessionId);

    /** 统计指定用户的会话数量 */
    long countByUserId(String userId);

    /** 统计指定用户某状态的会话数量 */
    long countByUserIdAndStatus(String userId, String status);

    /** 近 10 条会话（最近活动展示用，避免全量加载实体） */
    List<InterviewSessionEntity> findTop10ByUserIdOrderByCreatedAtDesc(String userId);

    /** 指定用户某状态的会话，按创建时间升序（趋势聚合用） */
    List<InterviewSessionEntity> findByUserIdAndStatusOrderByCreatedAtAsc(String userId, String status);
}
