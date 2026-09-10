package com.example.interview.repository;

import com.example.interview.entity.JobFavoriteEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 岗位收藏 JPA Repository
 */
@Repository
public interface JobFavoriteRepository extends JpaRepository<JobFavoriteEntity, Long> {

    /** 查询用户全部岗位收藏，按收藏时间倒序 */
    List<JobFavoriteEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    /** 查询用户对某岗位的收藏记录（幂等判定） */
    Optional<JobFavoriteEntity> findByUserIdAndJobId(String userId, Long jobId);

    /** 统计用户收藏数量 */
    long countByUserId(String userId);
}
