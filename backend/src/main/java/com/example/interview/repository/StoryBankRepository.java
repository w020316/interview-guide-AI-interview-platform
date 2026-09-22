package com.example.interview.repository;

import com.example.interview.entity.StoryBankEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 面试故事库 JPA Repository（v1.36.0）
 */
@Repository
public interface StoryBankRepository extends JpaRepository<StoryBankEntity, Long> {

    /** 查询用户全部故事，按最近更新倒序（故事库主视图） */
    List<StoryBankEntity> findByUserIdOrderByUpdatedAtDesc(String userId);

    /** 归属校验查询（防 IDOR）：非本人返回 empty */
    Optional<StoryBankEntity> findByIdAndUserId(Long id, String userId);

    /** 统计用户故事总数 */
    long countByUserId(String userId);
}
