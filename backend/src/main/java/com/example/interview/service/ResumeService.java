package com.example.interview.service;

import com.example.interview.common.ResourceNotFoundException;
import com.example.interview.entity.ResumeEntity;
import com.example.interview.repository.ResumeRepository;
import com.example.interview.util.JsonRepairUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 简历业务服务
 * - 保存简历内容与 AI 分析结果
 * - 查询用户简历历史
 * - 查询简历详情
 */
@Service
public class ResumeService {

    private static final Logger log = LoggerFactory.getLogger(ResumeService.class);

    @Autowired
    private ResumeRepository resumeRepository;

    @Autowired
    private ObjectMapper objectMapper;

    /** G2：删除简历前判定引用关系，并支持显式级联清理派生会话 */
    @Autowired
    private com.example.interview.repository.InterviewSessionRepository sessionRepository;

    @Autowired
    private com.example.interview.repository.InterviewQuestionRepository questionRepository;

    /**
     * 保存简历分析结果
     *
     * @param userId         用户 ID
     * @param resumeText     简历文本
     * @param targetJob      目标岗位
     * @param analysisResult AI 分析结果 JSON 字符串
     * @return 已持久化的简历实体
     */
    @Transactional
    public ResumeEntity saveResume(String userId, String resumeText, String targetJob, String analysisResult) {
        // 先修复 JSON 再持久化，保证数据库存的是合法 JSON；修复后仍非法则用兜底 JSON
        // （避免 JSONB 字段写入失败）。null/空白也走兜底。
        String safeResult = (analysisResult == null || analysisResult.isBlank())
                ? JsonRepairUtil.FALLBACK_JSON
                : JsonRepairUtil.repairOrFallback(analysisResult, "resume-persist", JsonRepairUtil.FALLBACK_JSON);

        // 解析综合评分。
        //
        // v1.47.0（第六轮 P1-01）：兜底/失败结果**不得**把 overallScore 写进数字列。
        // 此前兜底串自带 "overallScore":0，于是「AI 解析失败」被落库成「0 分」，
        // 与真实 0 分无法区分，污染 avgResumeScore 与前端展示。
        // 判定依据（**不是** overallScore == 0，那会误伤真实 0 分）：
        //   ① safeResult 含兜底标记 "AI_PARSE_FAILED"；
        //   ② 解析出的 dimensions 不是非空数组（无维度 = 无有效分析）。
        Integer overallScore = null;
        boolean failed = safeResult.contains("AI_PARSE_FAILED");
        try {
            Map<?, ?> obj = objectMapper.readValue(safeResult, Map.class);
            Object dims = obj.get("dimensions");
            if (!(dims instanceof List<?> dl) || dl.isEmpty()) {
                failed = true;
            }
            if (!failed) {
                Object score = obj.get("overallScore");
                if (score instanceof Number n) {
                    overallScore = n.intValue();
                } else if (score instanceof String s && !s.isBlank()) {
                    // AI 可能返回字符串 "75"，强制转换
                    try {
                        overallScore = Integer.parseInt(s.trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        } catch (Exception e) {
            log.warn("解析 overallScore 失败，按失败结果处理（不写入评分）：{}", e.getMessage());
            failed = true;
        }
        if (failed) {
            log.warn("简历分析结果为兜底/无效内容，overallScore 置空不落库 userId={}", userId);
        }

        ResumeEntity resume = ResumeEntity.builder()
                .userId(userId)
                .content(resumeText)
                .targetJob(targetJob)
                .overallScore(overallScore)
                .analysisResult(safeResult)
                .build();
        return resumeRepository.save(resume);
    }

    /**
     * 查询指定用户的简历历史（按创建时间倒序）
     */
    public List<ResumeEntity> listByUser(String userId) {
        return resumeRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * 通过简历 ID 获取详情（带越权校验）
     * - 简历不存在：抛 ResourceNotFoundException（GlobalExceptionHandler 映射为 404）
     * - 简历存在但非本人：抛 AccessDeniedException（GlobalExceptionHandler 映射为 403，语义更准确）
     *
     * v1.16 起：越权场景从 IllegalArgumentException 改为 AccessDeniedException，
     * 使 HTTP 状态码从 403 替代原 400，与 InterviewSessionController 的 IDOR 处理对齐
     * v1.44.0（第三轮 P3-01）：「简历不存在」从 IllegalArgumentException（400）改为
     * ResourceNotFoundException（404），与 docs/api-error-contract.md 及 GET /api/jobs/{id} 对齐
     */
    public ResumeEntity getByIdAndUser(Long id, String userId) {
        ResumeEntity resume = resumeRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("简历不存在：" + id));
        if (!userId.equals(resume.getUserId())) {
            throw new AccessDeniedException("无权访问该简历");
        }
        return resume;
    }

    /**
     * 删除一份简历（G2，2026-10-18）。
     *
     * <p><b>为什么不能直接 deleteById</b>：`interview_session.resume_id` 是一条
     * **没有 ON DELETE 子句的普通外键**。直接删会触发外键约束失败，对用户表现为 500。
     * 因此必须先判定引用，再在两种语义里选一种：
     * <ul>
     *   <li>{@code cascade=false}（默认）：被引用即拒绝，抛 {@link ConflictException} → HTTP 409，
     *       文案带上「N 场面试」，让用户知道拒绝的原因而不是撞一个不明所以的 500；</li>
     *   <li>{@code cascade=true}：用户已明确同意 —— 在同一事务内先删题目、再删会话、最后删简历。
     *       顺序不能反：先删简历会立刻撞外键。</li>
     * </ul>
     *
     * <p>归属校验复用 {@link #getByIdAndUser}：不存在 → 404，非本人 → 403。
     * 不做「静默删除他人数据」这种便利——那是越权。
     *
     * @return 被级联删除的会话数量（0 表示没有派生会话）
     */
    @Transactional
    public int deleteForUser(Long id, String userId, boolean cascade) {
        ResumeEntity resume = getByIdAndUser(id, userId);
        List<com.example.interview.entity.InterviewSessionEntity> sessions =
                sessionRepository.findAllByResumeId(id);

        if (!sessions.isEmpty() && !cascade) {
            throw new com.example.interview.common.ConflictException(
                    "该简历已用于 " + sessions.size() + " 场面试，需确认后一并删除");
        }

        if (!sessions.isEmpty()) {
            List<String> sessionIds = sessions.stream()
                    .map(com.example.interview.entity.InterviewSessionEntity::getSessionId)
                    .toList();
            questionRepository.deleteBySessionIdIn(sessionIds);
            sessionRepository.deleteAll(sessions);
        }

        resumeRepository.delete(resume);
        log.info("用户 {} 删除简历 {}，级联删除会话 {} 场", userId, id, sessions.size());
        return sessions.size();
    }

    /** 统计用户简历数量 */
    public long countByUser(String userId) {        return resumeRepository.countByUserId(userId);
    }
}
