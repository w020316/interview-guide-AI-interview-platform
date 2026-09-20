package com.example.interview.service;

import com.example.interview.entity.InterviewQuestionEntity;
import com.example.interview.entity.InterviewSessionEntity;
import com.example.interview.repository.InterviewQuestionRepository;
import com.example.interview.repository.InterviewSessionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 面试会话业务服务
 * - 创建/查询/完成会话
 * - 保存题目、用户回答、评估结果
 * - 知识库关联：错题总结、题目汇总
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
     *
     * <p>P1（B-17，2026-09-20）：service 层补归属校验。此前仅 controller 层补偿 403，
     * 一旦新增内部调用方即出现越权写。与 {@link #saveAnswer} 的既有模式保持一致，
     * 形成纵深防御。
     *
     * @param sessionId     会话 ID
     * @param currentUserId 当前登录用户 ID（用于越权校验）
     */
    @Transactional
    public InterviewSessionEntity finishSession(String sessionId, String currentUserId) {
        InterviewSessionEntity session = getBySessionId(sessionId);
        if (!currentUserId.equals(session.getUserId())) {
            throw new IllegalArgumentException("无权操作他人会话");
        }
        session.setStatus("FINISHED");
        return sessionRepository.save(session);
    }

    // ─────────────────────────────── 题目管理 ───────────────────────────────

    /**
     * 批量保存 AI 生成的面试题目
     *
     * <p>P1（B-17，2026-09-20）：service 层补归属校验，见 {@link #finishSession}。
     *
     * @param sessionId     会话 ID
     * @param questions     题目列表
     * @param currentUserId 当前登录用户 ID（用于越权校验）
     * @return 已持久化的题目列表
     */
    @Transactional
    public List<InterviewQuestionEntity> saveQuestions(String sessionId,
                                                        List<InterviewQuestionEntity> questions,
                                                        String currentUserId) {
        InterviewSessionEntity session = getBySessionId(sessionId);
        if (!currentUserId.equals(session.getUserId())) {
            throw new IllegalArgumentException("无权向他人会话写入题目");
        }
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
     * @param currentUserId   当前登录用户 ID（用于越权校验）
     */
    @Transactional
    public InterviewQuestionEntity saveAnswer(Long questionId, String userAnswer, Integer evaluationScore, String currentUserId) {
        InterviewQuestionEntity question = questionRepository.findById(questionId)
                .orElseThrow(() -> new IllegalArgumentException("题目不存在：" + questionId));
        // 越权校验：题目所属会话必须归当前用户所有
        InterviewSessionEntity session = sessionRepository.findBySessionId(question.getSessionId())
                .orElseThrow(() -> new IllegalArgumentException("会话不存在：" + question.getSessionId()));
        if (!currentUserId.equals(session.getUserId())) {
            throw new IllegalArgumentException("无权操作他人题目");
        }
        question.setUserAnswer(userAnswer);
        question.setEvaluationScore(evaluationScore);
        return questionRepository.save(question);
    }

    // ─────────────────────────────── 知识库关联 ───────────────────────────────

    /**
     * 查询用户所有面试题目（关联所有会话）
     * 用于知识库"题目汇总"功能
     */
    public List<InterviewQuestionEntity> listAllQuestionsByUser(String userId) {
        List<InterviewSessionEntity> sessions = sessionRepository.findByUserIdOrderByCreatedAtDesc(userId);
        if (sessions.isEmpty()) {
            return List.of();
        }
        List<String> sessionIds = sessions.stream()
                .map(InterviewSessionEntity::getSessionId)
                .collect(Collectors.toList());
        return questionRepository.findBySessionIdInOrderByCreatedAtDesc(sessionIds);
    }

    /**
     * 查询用户错题列表（评分低于阈值，默认 60 分）
     * 用于知识库"错题总结"功能
     *
     * @param userId    用户 ID
     * @param threshold 错题阈值（评分 < threshold 视为错题）
     * @return 错题列表，按时间倒序
     */
    public List<InterviewQuestionEntity> listWrongQuestionsByUser(String userId, int threshold) {
        // P1/S-02（2026-09-20）：过滤下推数据库。此前先全量加载该用户所有题目再在内存过滤，
        // 题目数增长后是明显的内存/带宽浪费（错题总结、智能体工具每轮都可能调用）。
        List<String> sessionIds = listSessionIdsByUser(userId);
        if (sessionIds.isEmpty()) {
            return List.of();
        }
        return questionRepository.findBySessionIdInAndEvaluationScoreLessThanOrderByCreatedAtDesc(
                sessionIds, threshold);
    }

    /**
     * 分页查询用户最近的题目（知识库"最近题目"）
     *
     * <p>P1/S-02（2026-09-20）：分页与计数下推数据库。此前全量加载后在内存
     * {@code limit(limit)}，且 total 取的是被截断后的条数——分页语义失真。
     * 现由数据库返回当前页与真实总数。
     *
     * @param userId 用户 ID
     * @param limit  每页条数（1-100）
     * @return Spring Data 分页对象：getContent() 当前页、getTotalElements() 真实总数
     */
    public Page<InterviewQuestionEntity> listRecentQuestionsByUser(String userId, int limit) {
        List<String> sessionIds = listSessionIdsByUser(userId);
        if (sessionIds.isEmpty()) {
            return Page.empty();
        }
        Pageable pageable = PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "createdAt"));
        return questionRepository.findBySessionIdIn(sessionIds, pageable);
    }

    /** 取用户全部会话 ID（供题目查询收敛用）；无会话返回空列表 */
    private List<String> listSessionIdsByUser(String userId) {
        List<InterviewSessionEntity> sessions = sessionRepository.findByUserIdOrderByCreatedAtDesc(userId);
        return sessions.stream()
                .map(InterviewSessionEntity::getSessionId)
                .collect(Collectors.toList());
    }

    /**
     * 题目汇总统计（按分类、难度聚合）
     * 返回结构：
     * {
     *   "totalQuestions": 50,
     *   "answeredQuestions": 42,
     *   "wrongQuestions": 12,
     *   "averageScore": 72.5,
     *   "byCategory": [{"category":"Java基础","total":20,"answered":18,"wrong":5,"avgScore":70.0}, ...],
     *   "byDifficulty": [{"difficulty":"EASY","total":15,"answered":14,"wrong":2,"avgScore":80.0}, ...]
     * }
     */
    public Map<String, Object> questionSummary(String userId) {
        List<InterviewQuestionEntity> all = listAllQuestionsByUser(userId);

        long total = all.size();
        long answered = all.stream()
                .filter(q -> q.getUserAnswer() != null && !q.getUserAnswer().isBlank())
                .count();
        long wrong = all.stream()
                .filter(q -> q.getEvaluationScore() != null && q.getEvaluationScore() < 60)
                .count();

        double avgScore = all.stream()
                .filter(q -> q.getEvaluationScore() != null)
                .mapToInt(InterviewQuestionEntity::getEvaluationScore)
                .average()
                .orElse(0.0);

        // 按分类聚合
        Map<String, List<InterviewQuestionEntity>> byCategory = all.stream()
                .filter(q -> q.getCategory() != null)
                .collect(Collectors.groupingBy(InterviewQuestionEntity::getCategory));

        List<Map<String, Object>> categoryStats = byCategory.entrySet().stream()
                .map(e -> buildStat(e.getKey(), "category", e.getValue()))
                .sorted((a, b) -> Long.compare((long) b.get("total"), (long) a.get("total")))
                .collect(Collectors.toList());

        // 按难度聚合
        Map<String, List<InterviewQuestionEntity>> byDifficulty = all.stream()
                .filter(q -> q.getDifficulty() != null)
                .collect(Collectors.groupingBy(InterviewQuestionEntity::getDifficulty));

        List<Map<String, Object>> difficultyStats = byDifficulty.entrySet().stream()
                .map(e -> buildStat(e.getKey(), "difficulty", e.getValue()))
                .collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("totalQuestions", total);
        result.put("answeredQuestions", answered);
        result.put("wrongQuestions", wrong);
        result.put("averageScore", Math.round(avgScore * 10) / 10.0);
        result.put("byCategory", categoryStats);
        result.put("byDifficulty", difficultyStats);
        return result;
    }

    /** 构建分组统计项 */
    private Map<String, Object> buildStat(String key, String keyName, List<InterviewQuestionEntity> questions) {
        long total = questions.size();
        long answered = questions.stream()
                .filter(q -> q.getUserAnswer() != null && !q.getUserAnswer().isBlank())
                .count();
        long wrong = questions.stream()
                .filter(q -> q.getEvaluationScore() != null && q.getEvaluationScore() < 60)
                .count();
        double avgScore = questions.stream()
                .filter(q -> q.getEvaluationScore() != null)
                .mapToInt(InterviewQuestionEntity::getEvaluationScore)
                .average()
                .orElse(0.0);

        Map<String, Object> stat = new HashMap<>();
        stat.put(keyName, key);
        stat.put("total", total);
        stat.put("answered", answered);
        stat.put("wrong", wrong);
        stat.put("avgScore", Math.round(avgScore * 10) / 10.0);
        return stat;
    }
}
