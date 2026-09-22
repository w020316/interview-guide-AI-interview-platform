package com.example.interview.repository;

import com.example.interview.entity.JobApplicationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 投递台账 JPA Repository（v1.35.0）
 */
@Repository
public interface JobApplicationRepository extends JpaRepository<JobApplicationEntity, Long> {

    /** 查询用户全部投递记录，按最近更新倒序（看板主视图） */
    List<JobApplicationEntity> findByUserIdOrderByUpdatedAtDesc(String userId);

    /** 查询用户对某岗位的投递记录（幂等判定，防重复加入） */
    Optional<JobApplicationEntity> findByUserIdAndJobId(String userId, Long jobId);

    /** 查询用户指定状态的投递记录（如「已投递待跟进」） */
    List<JobApplicationEntity> findByUserIdAndStatusOrderByUpdatedAtDesc(String userId, String status);

    /** 统计用户投递总数 */
    long countByUserId(String userId);

    /** 统计用户在指定状态下的记录数（看板分列计数） */
    long countByUserIdAndStatus(String userId, String status);
}
