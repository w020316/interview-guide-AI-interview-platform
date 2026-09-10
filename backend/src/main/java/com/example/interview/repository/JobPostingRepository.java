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
public interface JobPostingRepository extends JpaRepository<JobPostingEntity, Long> {

    /** 幂等 upsert 依据 */
    Optional<JobPostingEntity> findByPlatformAndExternalId(String platform, String externalId);

    /**
     * 多条件筛选搜索（全部条件可选）
     * 关键词匹配 岗位名/企业名/标签；行业/职位类型/招聘类型/来源精确匹配；地点模糊匹配
     * 按截止日期升序（临期优先），空截止日期排后
     */
    @Query("""
            SELECT j FROM JobPostingEntity j
            WHERE j.active = true
              AND (:keyword IS NULL OR LOWER(j.title) LIKE LOWER(CONCAT('%', :keyword, '%'))
                                 OR LOWER(j.companyName) LIKE LOWER(CONCAT('%', :keyword, '%'))
                                 OR LOWER(j.tags) LIKE LOWER(CONCAT('%', :keyword, '%')))
              AND (:industry IS NULL OR j.industry = :industry)
              AND (:jobType IS NULL OR j.jobType = :jobType)
              AND (:location IS NULL OR j.location LIKE CONCAT('%', :location, '%'))
              AND (:recruitType IS NULL OR j.recruitType = :recruitType)
              AND (:source IS NULL OR j.platform = :source)
            ORDER BY CASE WHEN j.deadline IS NULL THEN 1 ELSE 0 END, j.deadline ASC, j.updatedAt DESC
            """)
    Page<JobPostingEntity> search(@Param("keyword") String keyword,
                                  @Param("industry") String industry,
                                  @Param("jobType") String jobType,
                                  @Param("location") String location,
                                  @Param("recruitType") String recruitType,
                                  @Param("source") String source,
                                  Pageable pageable);

    /** 筛选面板元数据：有效岗位的行业去重列表 */
    @Query("SELECT DISTINCT j.industry FROM JobPostingEntity j WHERE j.active = true AND j.industry IS NOT NULL ORDER BY j.industry")
    List<String> findDistinctIndustries();

    /** 筛选面板元数据：有效岗位的职位类型去重列表 */
    @Query("SELECT DISTINCT j.jobType FROM JobPostingEntity j WHERE j.active = true AND j.jobType IS NOT NULL ORDER BY j.jobType")
    List<String> findDistinctJobTypes();

    /** 筛选面板元数据：有效岗位的数据来源去重列表 */
    @Query("SELECT DISTINCT j.platform FROM JobPostingEntity j WHERE j.active = true ORDER BY j.platform")
    List<String> findDistinctPlatforms();

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
