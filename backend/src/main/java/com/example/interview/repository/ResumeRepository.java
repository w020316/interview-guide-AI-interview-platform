package com.example.interview.repository;

import com.example.interview.entity.ResumeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
