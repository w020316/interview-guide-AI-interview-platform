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
}
