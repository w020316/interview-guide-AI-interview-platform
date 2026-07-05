package com.example.interview.repository;

import com.example.interview.entity.InterviewQuestionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * 面试题目 JPA Repository
 */
@Repository
public interface InterviewQuestionRepository extends JpaRepository<InterviewQuestionEntity, Long> {

    /** 查询指定会话的所有题目，按 ID 升序 */
    List<InterviewQuestionEntity> findBySessionIdOrderByIdAsc(String sessionId);

    /** 查询多个会话的所有题目，按创建时间倒序（用于知识库错题汇总） */
    List<InterviewQuestionEntity> findBySessionIdInOrderByCreatedAtDesc(Collection<String> sessionIds);

    /** 统计指定会话的题目数量 */
    long countBySessionId(String sessionId);

    /** 删除指定会话的所有题目（级联删除场景） */
    void deleteBySessionId(String sessionId);
}
