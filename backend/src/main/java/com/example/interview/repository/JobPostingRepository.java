package com.example.interview.repository;

import com.example.interview.entity.JobPostingEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 岗位信息 Repository（招聘信息智能体）
 * 筛选查询统一走单条 @Query，避免内存过滤与 N+1
 */
public interface JobPostingRepository extends JpaRepository<JobPostingEntity, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<JobPostingEntity> {

    /** 幂等 upsert 依据 */
    Optional<JobPostingEntity> findByPlatformAndExternalId(String platform, String externalId);

    /** 全部有效岗位（供简历匹配推荐） */
    List<JobPostingEntity> findByActiveTrue();

    /** 筛选面板元数据：有效岗位的行业去重列表 */
    @Query("SELECT DISTINCT j.industry FROM JobPostingEntity j WHERE j.active = true AND j.industry IS NOT NULL ORDER BY j.industry")
    List<String> findDistinctIndustries();

    /** 筛选面板元数据：有效岗位的职位类型去重列表 */
    @Query("SELECT DISTINCT j.jobType FROM JobPostingEntity j WHERE j.active = true AND j.jobType IS NOT NULL ORDER BY j.jobType")
    List<String> findDistinctJobTypes();

    /** 筛选面板元数据：有效岗位的数据来源去重列表 */
    @Query("SELECT DISTINCT j.platform FROM JobPostingEntity j WHERE j.active = true ORDER BY j.platform")
    List<String> findDistinctPlatforms();

    /** 筛选面板元数据：有效岗位的学历要求去重列表（v1.26.0） */
    @Query("SELECT DISTINCT j.degree FROM JobPostingEntity j WHERE j.active = true AND j.degree IS NOT NULL ORDER BY j.degree")
    List<String> findDistinctDegrees();

    /** 筛选面板元数据：有效岗位的经验要求去重列表（v1.26.0） */
    @Query("SELECT DISTINCT j.experience FROM JobPostingEntity j WHERE j.active = true AND j.experience IS NOT NULL ORDER BY j.experience")
    List<String> findDistinctExperiences();

    /** 各招聘类型数量统计（秋招/社招/实习 Tab 角标） */
    @Query("SELECT j.recruitType, COUNT(j) FROM JobPostingEntity j WHERE j.active = true AND j.recruitType IS NOT NULL GROUP BY j.recruitType")
    List<Object[]> countByRecruitType();

    /** 最新一次数据更新时间 */
    @Query("SELECT MAX(j.updatedAt) FROM JobPostingEntity j")
    LocalDateTime findLastUpdatedAt();

    /** 清理长期失效数据：截止日期已过 N 天的岗位批量下架（active=false） */
    @Modifying
    @Query("UPDATE JobPostingEntity j SET j.active = false WHERE j.active = true AND j.deadline < :date")
    int deactivateExpired(@Param("date") LocalDate date);

    /** 清理长期未刷新的非内置数据（updated_at 早于阈值，避免第三方平台脏数据长期滞留） */
    @Modifying
    @Query("DELETE FROM JobPostingEntity j WHERE j.platform <> :builtin AND j.updatedAt < :date")
    int deleteStaleThirdParty(@Param("builtin") String builtin, @Param("date") LocalDateTime date);
}
    // 多条件筛选搜索已迁移至 JobAgentService 的 Specification 动态查询
    // （PostgreSQL 无法推断 @Query 中 IS NULL 参数类型，H2 本地正常而生产 500）

