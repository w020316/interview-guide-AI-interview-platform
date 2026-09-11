package com.example.interview.service;

import com.example.interview.entity.FavoriteQuestionEntity;
import com.example.interview.repository.FavoriteQuestionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 错题收藏业务服务
 * - 收藏/取消收藏面试题（快照式存储）
 * - 查询用户收藏列表、收藏过的题目 ID 集合、收藏数量
 */
@Service
public class FavoriteService {

    @Autowired
    private FavoriteQuestionRepository favoriteRepository;

    /**
     * 查询用户全部收藏，按收藏时间倒序
     */
    public List<FavoriteQuestionEntity> listByUser(String userId) {
        return favoriteRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * 查询用户收藏过的原题目 ID 集合（用于前端高亮收藏状态与幂等判定）
     */
    public Set<Long> listFavoriteQuestionIds(String userId) {
        return favoriteRepository.findByUserId(userId).stream()
                .map(FavoriteQuestionEntity::getQuestionId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
    }

    /**
     * 切换收藏：若该题已收藏则取消，否则按提供的快照新增收藏。
     *
     * @param favoriteId 已存在的收藏 ID（用于通过收藏 ID 直接取消；可为 null 走题目 ID 判断）
     * @param userId     当前用户 ID
     * @param questionId 原题目 ID
     * @param snapshot   收藏时保存的题目快照（仅新增时使用）
     * @return 切换后是否处于收藏状态
     */
    @Transactional
    public boolean toggle(Long favoriteId, String userId, Long questionId, FavoriteQuestionEntity snapshot) {
        // 优先按收藏 ID 取消（用于收藏夹列表内「移除」）
        if (favoriteId != null) {
            favoriteRepository.findById(favoriteId)
                    .filter(f -> f.getUserId().equals(userId)) // 越权校验
                    .ifPresent(favoriteRepository::delete);
            return false;
        }
        // 其次按原题目 ID 判定是否已收藏
        if (questionId != null) {
            var existing = favoriteRepository.findByUserIdAndQuestionId(userId, questionId);
            if (existing.isPresent()) {
                favoriteRepository.delete(existing.get());
                return false;
            }
        }
        // 新增收藏
        FavoriteQuestionEntity entity = snapshot.toBuilder().userId(userId).id(null).build();
        // 避免重复收藏同一题（并发/重复点击兜底）
        if (entity.getQuestionId() != null
                && favoriteRepository.findByUserIdAndQuestionId(userId, entity.getQuestionId()).isPresent()) {
            return true;
        }
        favoriteRepository.save(entity);
        return true;
    }

    /**
     * 手动添加自定义题目到题库（v1.28.0）
     * 与 toggle 不同：不依赖原题目 ID，questionId 恒为空，每次新增一条自定义题。
     */
    @Transactional
    public FavoriteQuestionEntity addManual(String userId, String question,
                                            String category, String difficulty, String referenceAnswer) {
        FavoriteQuestionEntity entity = FavoriteQuestionEntity.builder()
                .userId(userId)
                .question(question)
                .category(category)
                .difficulty(difficulty)
                .referenceAnswer(referenceAnswer)
                .build();
        FavoriteQuestionEntity saved = favoriteRepository.save(entity);
        favoriteRepository.flush();
        return saved;
    }

    /**
     * 统计用户收藏数量
     */
    public long countByUser(String userId) {
        return favoriteRepository.countByUserId(userId);
    }
}