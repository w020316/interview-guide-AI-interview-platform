package com.example.interview.repository;

import com.example.interview.entity.FavoriteQuestionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 错题收藏 JPA Repository
 */
@Repository
public interface FavoriteQuestionRepository extends JpaRepository<FavoriteQuestionEntity, Long> {

    /** 查询用户所有收藏（默认按收藏时间倒序） */
    List<FavoriteQuestionEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    /** 按用户+原题目 ID 查询（用于幂等去重/取消收藏判定） */
    Optional<FavoriteQuestionEntity> findByUserIdAndQuestionId(String userId, Long questionId);

    /** 统计某用户收藏数量 */
    long countByUserId(String userId);

    /** 查询用户收藏过的原题目 ID 集合 */
    List<FavoriteQuestionEntity> findByUserId(String userId);
}