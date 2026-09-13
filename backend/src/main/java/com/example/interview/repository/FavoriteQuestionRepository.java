package com.example.interview.repository;

import com.example.interview.entity.FavoriteQuestionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 错题收藏 JPA Repository
 */
@Repository
public interface FavoriteQuestionRepository extends JpaRepository<FavoriteQuestionEntity, Long> {

    /** 查询用户所有收藏（默认按收藏时间倒序） */
    List<FavoriteQuestionEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    /**
     * 按用户+原题目 ID 删除（P1-06）：派生删除可一并清掉历史遗留的重复收藏行，
     * 并返回删除行数作为「是否原本已收藏」的判定依据。
     * 替代原 findByUserIdAndQuestionId 单行查询——该查询遇重复行会抛
     * IncorrectResultSizeDataAccessException，导致该题收藏切换永久 500。
     */
    long deleteByUserIdAndQuestionId(String userId, Long questionId);

    /** 统计某用户收藏数量 */
    long countByUserId(String userId);

    /** 查询用户收藏过的原题目 ID 集合 */
    List<FavoriteQuestionEntity> findByUserId(String userId);
}