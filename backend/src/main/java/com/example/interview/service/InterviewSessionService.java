package com.example.interview.service;

import com.example.interview.entity.InterviewQuestionEntity;
import com.example.interview.entity.InterviewSessionEntity;
import com.example.interview.repository.InterviewQuestionRepository;
import com.example.interview.repository.InterviewSessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 面试会话业务服务
 * - 创建/查询/完成会话
 * - 保存题目、用户回答、评估结果
 */
@Service
public class InterviewSessionService {

    @Autowired
    private InterviewSessionRepository sessionRepository;

    @Autowired
    private InterviewQuestionRepository questionRepository;

    // ─────────────────────────────── 会话管理 ───────────────────────────────

    /**
     * 创建新面试会话
     *
     * @param userId         用户 ID
     * @param jobDescription 目标岗位描述
     * @param resumeId       关联简历 ID（可为 null）
     * @return 创建完成的会话实体
     */
    @Transactional
    public InterviewSessionEntity createSession(String userId, String jobDescription, Long resumeId) {
        InterviewSessionEntity session = InterviewSessionEntity.builder()
                .sessionId(UUID.randomUUID().toString())
                .userId(userId)
                .jobDescription(jobDescription)
                .resumeId(resumeId)
                .status("ONGOING")
                .build();
        return sessionRepository.save(session);
    }

    /**
     * 查询指定用户的历史会话列表
     */
    public List<InterviewSessionEntity> listByUser(String userId) {
        return sessionRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * 通过 sessionId 获取会话详情
     */
    public InterviewSessionEntity getBySessionId(String sessionId) {
        return sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在：" + sessionId));
    }

    /**
     * 将会话标记为已完成
     */
    @Transactional
    public InterviewSessionEntity finishSession(String sessionId) {
        InterviewSessionEntity session = getBySessionId(sessionId);
        session.setStatus("FINISHED");
        return sessionRepository.save(session);
    }

    // ─────────────────────────────── 题目管理 ───────────────────────────────

    /**
     * 批量保存 AI 生成的面试题目
     *
     * @param sessionId 会话 ID
     * @param questions 题目列表
     * @return 已持久化的题目列表
     */
    @Transactional
    public List<InterviewQuestionEntity> saveQuestions(String sessionId,
                                                        List<InterviewQuestionEntity> questions) {
        // 确保会话存在
        getBySessionId(sessionId);
        questions.forEach(q -> q.setSessionId(sessionId));
        return questionRepository.saveAll(questions);
    }

    /**
     * 查询指定会话的所有题目
     */
    public List<InterviewQuestionEntity> listQuestions(String sessionId) {
        return questionRepository.findBySessionIdOrderByIdAsc(sessionId);
    }

    /**
     * 保存用户回答及 AI 评估结果
     *
     * @param questionId      题目 ID
     * @param userAnswer      用户回答文本
     * @param evaluationScore AI 评分（0-100）
     */
    @Transactional
    public InterviewQuestionEntity saveAnswer(Long questionId, String userAnswer, Integer evaluationScore) {
        InterviewQuestionEntity question = questionRepository.findById(questionId)
                .orElseThrow(() -> new IllegalArgumentException("题目不存在：" + questionId));
        question.setUserAnswer(userAnswer);
        question.setEvaluationScore(evaluationScore);
        return questionRepository.save(question);
    }
}
