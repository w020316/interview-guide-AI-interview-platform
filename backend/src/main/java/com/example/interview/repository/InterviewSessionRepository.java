package com.example.interview.repository;

import com.example.interview.entity.InterviewSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * 仅取 (sessionId, jobDescription) 投影（B-10）。
     *
     * <p>错题总结只需要这两列来拼「题目 → 岗位」映射；此前用
     * {@link #findByUserIdOrderByCreatedAtDesc} 会把会话实体的全部列（含大量文本字段）拉进内存。
     * 投影查询把加载量降到两列。
     */
    @Query("SELECT s.sessionId AS sessionId, s.jobDescription AS jobDescription "
            + "FROM InterviewSessionEntity s WHERE s.userId = :userId ORDER BY s.createdAt DESC")
    List<SessionJobProjection> findSessionIdAndJobByUserId(@Param("userId") String userId);

    /** sessionId → jobDescription 的轻量投影 */
    interface SessionJobProjection {
        String getSessionId();
        String getJobDescription();
    }

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
