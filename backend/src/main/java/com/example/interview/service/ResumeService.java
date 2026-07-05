package com.example.interview.service;

import com.example.interview.entity.ResumeEntity;
import com.example.interview.repository.ResumeRepository;
import com.example.interview.util.JsonRepairUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
        // 先修复 JSON 再持久化，保证数据库存的是合法 JSON
        String safeResult = analysisResult;
        if (analysisResult != null && !analysisResult.isBlank()) {
            safeResult = JsonRepairUtil.repairAndLog(analysisResult, "resume-persist");
        }

        // 解析综合评分（容错：解析失败则不存）
        Integer overallScore = null;
        try {
            if (safeResult != null && !safeResult.isBlank()) {
                Map<?, ?> obj = objectMapper.readValue(safeResult, Map.class);
                Object score = obj.get("overallScore");
                if (score instanceof Number n) {
                    overallScore = n.intValue();
                }
            }
        } catch (Exception e) {
            log.warn("解析 overallScore 失败：{}", e.getMessage());
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
     */
    public ResumeEntity getByIdAndUser(Long id, String userId) {
        return resumeRepository.findById(id)
                .filter(r -> userId.equals(r.getUserId()))
                .orElseThrow(() -> new IllegalArgumentException("简历不存在或无权访问"));
    }

    /** 统计用户简历数量 */
    public long countByUser(String userId) {
        return resumeRepository.countByUserId(userId);
    }
}
