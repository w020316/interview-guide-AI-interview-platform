package com.example.interview.service;

import com.example.interview.entity.JobFavoriteEntity;
import com.example.interview.entity.JobPostingEntity;
import com.example.interview.repository.JobFavoriteRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 岗位收藏业务服务
 * - 收藏/取消收藏岗位（快照式存储，原岗位下架后仍可回看）
 * - 查询用户收藏列表、已收藏岗位 ID 集合、收藏数量
 * - 截止日期临近提醒数据源（read-time 计算，无需定时任务）
 */
@Service
public class JobFavoriteService {

    @Autowired
    private JobFavoriteRepository jobFavoriteRepository;

    /**
     * 查询用户全部岗位收藏，按收藏时间倒序
     */
    public List<JobFavoriteEntity> listByUser(String userId) {
        return jobFavoriteRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /**
     * 查询用户已收藏的岗位 ID 集合（用于前端高亮收藏状态）
     */
    public Set<Long> listFavoriteJobIds(String userId) {
        return jobFavoriteRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(JobFavoriteEntity::getJobId)
                .collect(Collectors.toSet());
    }

    /**
     * 切换收藏：已收藏则取消，未收藏则按岗位快照新增
     *
     * @param userId 当前用户 ID
     * @param job    岗位实体（新增时取快照；null 视为岗位不存在）
     * @return 切换后是否处于收藏状态
     */
    @Transactional
    public boolean toggle(String userId, JobPostingEntity job) {
        var existing = jobFavoriteRepository.findByUserIdAndJobId(userId, job.getId());
        if (existing.isPresent()) {
            jobFavoriteRepository.delete(existing.get());
            return false;
        }
        // 并发/重复点击兜底：再次确认后仍不存在才新增
        JobFavoriteEntity entity = JobFavoriteEntity.builder()
                .userId(userId)
                .jobId(job.getId())
                .title(job.getTitle())
                .companyName(job.getCompanyName())
                .platform(job.getPlatform())
                .location(job.getLocation())
                .salary(job.getSalary())
                .deadline(job.getDeadline())
                .applyUrl(job.getApplyUrl())
                .build();
        if (jobFavoriteRepository.findByUserIdAndJobId(userId, job.getId()).isPresent()) {
            return true;
        }
        jobFavoriteRepository.save(entity);
        return true;
    }

    /**
     * 统计用户收藏数量
     */
    public long countByUser(String userId) {
        return jobFavoriteRepository.countByUserId(userId);
    }
}
