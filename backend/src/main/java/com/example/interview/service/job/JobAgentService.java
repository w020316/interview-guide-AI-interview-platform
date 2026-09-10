package com.example.interview.service.job;

import com.example.interview.entity.JobPostingEntity;
import com.example.interview.repository.JobPostingRepository;
import com.example.interview.service.job.JobPlatformAdapter.JobDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 招聘信息智能体服务
 *
 * 职责：
 * 1. 调度各平台适配器拉取岗位并幂等 upsert（保证数据准确性与时效性）
 * 2. AI 智能分类补全（行业/职位类型/标签）
 * 3. 过期岗位自动下架 + 长期失效数据清理（数据更新机制）
 * 4. 多条件筛选搜索（关键词/行业/职位类型/地点/招聘类型/来源）
 */
@Service
public class JobAgentService {

    private static final Logger log = LoggerFactory.getLogger(JobAgentService.class);

    private final JobPostingRepository repository;
    private final List<JobPlatformAdapter> adapters;
    private final HttpJobPlatformAdapter httpAdapter;
    private final JobClassifyService classifyService;

    public JobAgentService(JobPostingRepository repository,
                           List<JobPlatformAdapter> adapters,
                           HttpJobPlatformAdapter httpAdapter,
                           JobClassifyService classifyService) {
        this.repository = repository;
        this.adapters = adapters;
        this.httpAdapter = httpAdapter;
        this.classifyService = classifyService;
    }

    /** 刷新结果统计 */
    public record RefreshResult(int upserted, int inserted, int updated, int expired, int removed) {
    }

    /**
     * 全量刷新：拉取所有启用平台数据并入库
     * - 内置精选等适配器：走 fetch()，platform = adapter.platform()
     * - 第三方平台（智联招聘/前程无忧/BOSS直聘）：走 fetchAllByPlatform()，按平台展示名分组入库
     */
    @Transactional
    public RefreshResult refresh() {
        int upserted = 0;
        int inserted = 0;
        int updated = 0;

        // 分组收集：平台名 -> 岗位列表
        Map<String, List<JobDto>> platformJobs = new LinkedHashMap<>();
        for (JobPlatformAdapter adapter : adapters) {
            if (adapter == httpAdapter) {
                continue; // 第三方平台单独处理
            }
            try {
                platformJobs.put(adapter.platform(), adapter.fetch());
            } catch (Exception e) {
                log.warn("平台 {} 岗位拉取失败：{}", adapter.platform(), e.getMessage());
            }
        }
        try {
            platformJobs.putAll(httpAdapter.fetchAllByPlatform());
        } catch (Exception e) {
            log.warn("第三方平台岗位拉取失败：{}", e.getMessage());
        }

        for (var entry : platformJobs.entrySet()) {
            String platform = entry.getKey();
            List<JobDto> jobs = entry.getValue();
            if (jobs == null || jobs.isEmpty()) {
                continue;
            }
            try {
                // AI 分类：仅为缺失行业/职位类型的数据补全
                List<JobDto> needClassify = jobs.stream()
                        .filter(j -> isBlank(j.industry()) || isBlank(j.jobType()))
                        .toList();
                Map<String, JobClassifyService.Classification> clsMap = new HashMap<>();
                if (!needClassify.isEmpty()) {
                    List<JobClassifyService.Classification> cls = classifyService.classifyBatch(needClassify);
                    for (int i = 0; i < needClassify.size() && i < cls.size(); i++) {
                        clsMap.put(needClassify.get(i).externalId(), cls.get(i));
                    }
                }

                for (JobDto dto : jobs) {
                    boolean isNew = upsert(platform, dto, clsMap.get(dto.externalId()));
                    upserted++;
                    if (isNew) inserted++;
                    else updated++;
                }
                log.info("平台 {} 岗位刷新完成：{} 条", platform, jobs.size());
            } catch (Exception e) {
                // 单平台失败不影响整体刷新
                log.warn("平台 {} 岗位刷新失败：{}", platform, e.getMessage());
            }
        }

        // 数据更新机制：截止日期已过 30 天的下架；非内置数据 60 天未更新则清理
        int expired = repository.deactivateExpired(LocalDate.now().minusDays(30));
        int removed = repository.deleteStaleThirdParty("内置精选", LocalDateTime.now().minusDays(60));

        log.info("招聘信息刷新完成：新增 {} / 更新 {} / 下架 {} / 清理 {}", inserted, updated, expired, removed);
        return new RefreshResult(upserted, inserted, updated, expired, removed);
    }

    /** 幂等 upsert，返回是否为新增 */
    private boolean upsert(String platform, JobDto dto, JobClassifyService.Classification cls) {
        var existing = repository.findByPlatformAndExternalId(platform, dto.externalId());
        JobPostingEntity entity;
        boolean isNew = existing.isEmpty();
        if (isNew) {
            entity = JobPostingEntity.builder()
                    .platform(platform)
                    .externalId(dto.externalId())
                    .build();
        } else {
            entity = existing.get();
        }
        entity.setTitle(dto.title());
        entity.setCompanyName(dto.companyName());
        entity.setApplyUrl(dto.applyUrl());
        entity.setDescription(dto.description());
        entity.setRequirements(dto.requirements());
        entity.setRecruitType(isBlank(dto.recruitType()) ? "AUTUMN" : dto.recruitType());
        entity.setDeadline(dto.deadline());
        entity.setLocation(dto.location());
        entity.setSalary(dto.salary());
        entity.setDegree(dto.degree());
        entity.setExperience(dto.experience());
        entity.setActive(true);

        // 行业/职位类型：优先用平台数据，缺失时用 AI/规则分类补全
        entity.setIndustry(!isBlank(dto.industry()) ? dto.industry()
                : (cls != null && !isBlank(cls.industry()) ? cls.industry() : entity.getIndustry()));
        entity.setJobType(!isBlank(dto.jobType()) ? dto.jobType()
                : (cls != null && !isBlank(cls.jobType()) ? cls.jobType() : entity.getJobType()));
        entity.setTags(!isBlank(dto.tags()) ? dto.tags()
                : (cls != null && !isBlank(cls.tags()) ? cls.tags() : entity.getTags()));

        repository.save(entity);
        return isNew;
    }

    /** 岗位详情（仅返回有效岗位） */
    public JobPostingEntity findById(Long id) {
        return repository.findById(id).filter(j -> Boolean.TRUE.equals(j.getActive())).orElse(null);
    }

    /**
     * 多条件筛选搜索
     *
     * @param recruitType AUTUMN 秋招 / SPRING 春招 / SOCIAL 社招 / INTERN 实习 / null 全部
     */
    public Page<JobPostingEntity> search(String keyword, String industry, String jobType,
                                         String location, String recruitType, String source,
                                         int page, int size) {
        return repository.search(
                blankToNull(keyword), blankToNull(industry), blankToNull(jobType),
                blankToNull(location), blankToNull(recruitType), blankToNull(source),
                PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 50)));
    }

    /** 筛选面板元数据：行业/职位类型/来源/各招聘类型数量/最近更新时间 */
    public Map<String, Object> meta() {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("industries", repository.findDistinctIndustries());
        meta.put("jobTypes", repository.findDistinctJobTypes());
        meta.put("sources", repository.findDistinctPlatforms());

        Map<String, Long> recruitCounts = new LinkedHashMap<>();
        for (Object[] row : repository.countByRecruitType()) {
            recruitCounts.put(String.valueOf(row[0]), (Long) row[1]);
        }
        meta.put("recruitCounts", recruitCounts);

        LocalDateTime last = repository.findLastUpdatedAt();
        meta.put("lastUpdatedAt", last == null ? null : last.toString());
        return meta;
    }

    /** 定时刷新调度（由 JobRefreshScheduler 调用与手动接口共用） */
    public List<String> enabledPlatforms() {
        List<String> names = new ArrayList<>();
        for (JobPlatformAdapter a : adapters) {
            if (a.isEnabled()) {
                names.add(a.platform());
            }
        }
        return names;
    }

    HttpJobPlatformAdapter httpAdapter() {
        return httpAdapter;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
