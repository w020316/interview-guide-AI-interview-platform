package com.example.interview.repository;

import com.example.interview.entity.InterviewQuestionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /** 指定用户全部题目平均评分（子查询聚合，避免全量加载题目实体） */
    @Query("select avg(q.evaluationScore) from InterviewQuestionEntity q " +
            "where q.sessionId in (select s.sessionId from InterviewSessionEntity s where s.userId = :userId)")
    Double avgEvaluationScoreByUserId(@Param("userId") String userId);

    /** 按会话聚合平均分与已评分题数（趋势图用）：每行 [sessionId, avg, count] */
    @Query("select q.sessionId, avg(q.evaluationScore), count(q.evaluationScore) " +
            "from InterviewQuestionEntity q " +
            "where q.sessionId in :sessionIds and q.evaluationScore is not null " +
            "group by q.sessionId")
    List<Object[]> avgScoreGroupBySession(@Param("sessionIds") Collection<String> sessionIds);
}
