package com.example.interview.repository;

import com.example.interview.entity.ResumeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 简历 JPA Repository
 */
@Repository
public interface ResumeRepository extends JpaRepository<ResumeEntity, Long> {

    /** 查询指定用户的所有简历，按创建时间倒序 */
    List<ResumeEntity> findByUserIdOrderByCreatedAtDesc(String userId);

    /** 统计指定用户的简历数量 */
    long countByUserId(String userId);

    /** 近 10 条简历（最近活动展示用，避免全量加载实体） */
    List<ResumeEntity> findTop10ByUserIdOrderByCreatedAtDesc(String userId);

    /** 简历平均分（数据库聚合，避免全量加载实体到内存计算） */
    @Query("select avg(r.overallScore) from ResumeEntity r where r.userId = :userId")
    Double avgOverallScoreByUserId(String userId);
}
