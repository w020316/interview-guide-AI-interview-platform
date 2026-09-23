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
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 岗位信息 Repository（招聘信息智能体）
 * 筛选查询统一走单条 @Query，避免内存过滤与 N+1
 */
public interface JobPostingRepository extends JpaRepository<JobPostingEntity, Long>, org.springframework.data.jpa.repository.JpaSpecificationExecutor<JobPostingEntity> {

    /** 幂等 upsert 依据 */
    Optional<JobPostingEntity> findByPlatformAndExternalId(String platform, String externalId);

    /**
     * 批量幂等 upsert 依据（P1/S-03）：一次 IN 查询取回存量实体，
     * 消除逐条 findByPlatformAndExternalId 造成的 N+1 查询
     */
    List<JobPostingEntity> findByPlatformAndExternalIdIn(String platform, Collection<String> externalIds);

    /** 全部有效岗位（供简历匹配推荐） */
    List<JobPostingEntity> findByActiveTrue();

    /** 有效岗位数量（管理后台总览） */
    long countByActiveTrue();

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

    /**
     * 各招聘类型数量统计（**仅限指定来源**，P2-08）。
     * 用于「海外远程」分栏：计数须与该分栏的实际结果一致。
     */
    @Query("SELECT j.recruitType, COUNT(j) FROM JobPostingEntity j WHERE j.active = true AND j.recruitType IS NOT NULL AND j.platform IN :platforms GROUP BY j.recruitType")
    List<Object[]> countByRecruitTypeInPlatforms(@Param("platforms") Collection<String> platforms);

    /**
     * 各招聘类型数量统计（**排除指定来源**，P2-08）。
     * 用于「全部国内」等国内分栏。调用方须保证 platforms 非空（JPQL 的 NOT IN 空集合无效）。
     */
    @Query("SELECT j.recruitType, COUNT(j) FROM JobPostingEntity j WHERE j.active = true AND j.recruitType IS NOT NULL AND j.platform NOT IN :platforms GROUP BY j.recruitType")
    List<Object[]> countByRecruitTypeNotInPlatforms(@Param("platforms") Collection<String> platforms);

    /**
     * 指定来源集合的有效岗位数（v1.38.0，「海外远程」分栏 Tab 角标）。
     *
     * <p>来源清单由适配器声明，因此这里用 IN 查询而不是给 platform 打布尔列——
     * 新增海外源只需适配器覆写 {{@link com.example.interview.service.job.JobPlatformAdapter#overseas()}}。
     */
    @Query("SELECT COUNT(j) FROM JobPostingEntity j WHERE j.active = true AND j.platform IN :platforms")
    long countActiveByPlatformIn(@Param("platforms") Collection<String> platforms);

    /** 最新一次数据更新时间 */
    @Query("SELECT MAX(j.updatedAt) FROM JobPostingEntity j")
    LocalDateTime findLastUpdatedAt();

    // ── 管理后台数据源视图（v1.37.0）──

    /**
     * 数据源维度统计：平台 -> 岗位总数 / 有效数。
     * 用于管理后台展示「岗位都来自哪些数据源、各自贡献多少、有多少已失效」。
     */
    @Query("SELECT j.platform, COUNT(j), SUM(CASE WHEN j.active = true THEN 1 ELSE 0 END) "
            + "FROM JobPostingEntity j GROUP BY j.platform ORDER BY COUNT(j) DESC")
    List<Object[]> countGroupByPlatform();

    /** 各数据源最近一次入库/更新时间（判断该源是否还在正常产出） */
    @Query("SELECT j.platform, MAX(j.updatedAt) FROM JobPostingEntity j GROUP BY j.platform")
    List<Object[]> findLastUpdatedAtByPlatform();

    /**
     * 指定时间后创建的岗位时间戳。
     *
     * <p>近 N 天趋势刻意不在 SQL 里按天分组：`DATE()` / `CAST(... AS date)` 在
     * H2（本地/测试）与 PostgreSQL（生产）上的写法不一致，容易「本地绿、线上红」。
     * 改为取回时间戳后在 Java 侧归组，方言无关且数据量可控。
     */
    @Query("SELECT j.createdAt FROM JobPostingEntity j WHERE j.createdAt >= :since")
    List<LocalDateTime> findCreatedAtSince(@Param("since") LocalDateTime since);

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

