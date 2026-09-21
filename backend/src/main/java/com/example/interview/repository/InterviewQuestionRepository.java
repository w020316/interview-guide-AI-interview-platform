package com.example.interview.repository;

import com.example.interview.entity.InterviewQuestionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /**
     * 分页查询多个会话的题目，按创建时间倒序（P1/S-02：聚合与计数下推数据库，
     * 避免全量加载后内存 limit 导致 total 失真）
     */
    Page<InterviewQuestionEntity> findBySessionIdIn(Collection<String> sessionIds, Pageable pageable);

    /**
     * 查询多个会话中评分低于阈值的题目，按创建时间倒序（P1/S-02：过滤下推数据库）。
     * SQL 语义上 evaluationScore 为 NULL 的行不满足 LessThan，自动排除，
     * 与原内存过滤 `evaluationScore != null && evaluationScore < threshold` 等价。
     */
    List<InterviewQuestionEntity> findBySessionIdInAndEvaluationScoreLessThanOrderByCreatedAtDesc(
            Collection<String> sessionIds, int threshold);

    /** 统计指定会话的题目数量 */
    long countBySessionId(String sessionId);

    /** 删除指定会话的所有题目（级联删除场景） */
    void deleteBySessionId(String sessionId);

    /** 指定用户全部题目平均评分（子查询聚合，避免全量加载题目实体） */
    @Query("select avg(q.evaluationScore) from InterviewQuestionEntity q " +
            "where q.sessionId in (select s.sessionId from InterviewSessionEntity s where s.userId = :userId)")
    Double avgEvaluationScoreByUserId(@Param("userId") String userId);

    /**
     * 指定用户的题目总数（v1.34.1，P3-9）。
     *
     * <p>背景：智能体每轮对话都要把用户画像（题目总数/错题数/平均分）注入 System Prompt，
     * 而原实现走 {@code questionSummary}——它会加载该用户**全部**题目实体后在内存里流式聚合。
     * 对话越多、题库越大，单轮开销越高，且每轮都重复一次。改为数据库聚合后为常数级查询。
     */
    @Query("select count(q.id) from InterviewQuestionEntity q " +
            "where q.sessionId in (select s.sessionId from InterviewSessionEntity s where s.userId = :userId)")
    long countByUserId(@Param("userId") String userId);

    /** 指定用户评分低于阈值的题目数（v1.34.1，P3-9 智能体画像用） */
    @Query("select count(q.id) from InterviewQuestionEntity q " +
            "where q.evaluationScore is not null and q.evaluationScore < :threshold " +
            "and q.sessionId in (select s.sessionId from InterviewSessionEntity s where s.userId = :userId)")
    long countWrongByUserId(@Param("userId") String userId, @Param("threshold") int threshold);

    /**
     * 指定用户按分类的题目数与平均分（v1.34.1，P3-9 智能体画像用）。
     * 每行 [category, count, avgScore]，仅返回有评分的题目。
     */
    @Query("select q.category, count(q.id), avg(q.evaluationScore) from InterviewQuestionEntity q " +
            "where q.category is not null and q.evaluationScore is not null " +
            "and q.sessionId in (select s.sessionId from InterviewSessionEntity s where s.userId = :userId) " +
            "group by q.category")
    List<Object[]> categoryStatsByUserId(@Param("userId") String userId);

    /** 按会话聚合平均分与已评分题数（趋势图用）：每行 [sessionId, avg, count] */
    @Query("select q.sessionId, avg(q.evaluationScore), count(q.evaluationScore) " +
            "from InterviewQuestionEntity q " +
            "where q.sessionId in :sessionIds and q.evaluationScore is not null " +
            "group by q.sessionId")
    List<Object[]> avgScoreGroupBySession(@Param("sessionIds") Collection<String> sessionIds);
}
