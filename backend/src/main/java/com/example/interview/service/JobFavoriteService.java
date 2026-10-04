package com.example.interview.service;

import com.example.interview.entity.JobFavoriteEntity;
import com.example.interview.entity.JobPostingEntity;
import com.example.interview.repository.JobFavoriteRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** P2-19：插入走独立新事务，撞唯一约束可在方法内捕获并幂等返回，不污染外层事务 */
    private TransactionTemplate insertTemplate;

    @PostConstruct
    void initInsertTemplate() {
        insertTemplate = new TransactionTemplate(transactionManager);
        insertTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

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
    /**
     * 切换收藏：已收藏则取消，未收藏则按岗位快照新增
     *
     * <p>P2-19：并发双击时后到者插入撞唯一约束 uk_job_favorite_user_job，此前被
     * DataAccessException 处理器转成 500"数据库操作失败"；改为捕获约束冲突后重查，
     * 按当前实际状态幂等返回。
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
        try {
            insertTemplate.executeWithoutResult(status -> jobFavoriteRepository.saveAndFlush(entity));
        } catch (DataIntegrityViolationException e) {
            // 并发窗口另一请求已插入同岗位收藏：重查确认并按幂等语义返回
            return jobFavoriteRepository.findByUserIdAndJobId(userId, job.getId()).isPresent();
        }
        return true;
    }

    /**
     * 统计用户收藏数量
     */
    public long countByUser(String userId) {
        return jobFavoriteRepository.countByUserId(userId);
    }

    /** 合法偏好档位（单一来源即实体常量） */
    public static final java.util.Set<String> ALL_PREFERENCES = java.util.Set.of(
            JobFavoriteEntity.PREFERENCE_STRONG,
            JobFavoriteEntity.PREFERENCE_ACCEPTABLE,
            JobFavoriteEntity.PREFERENCE_BACKUP,
            JobFavoriteEntity.PREFERENCE_EXCLUDED);

    /**
     * 设置岗位收藏偏好档位（第三批 H）。
     *
     * <p>幂等：同值重复提交无副作用。{@code preference} 为 {@code null}/空串表示「清除标记」（回到未标记）。
     * 非法取值抛 {@link IllegalArgumentException}（由全局处理器映射 400）。
     *
     * @param userId     当前用户 ID
     * @param jobId      岗位 ID
     * @param preference 档位（STRONG/ACCEPTABLE/BACKUP/EXCLUDED）或 null=清除
     * @return 更新后的收藏项；未收藏该岗位返回 {@code null}（由控制器转 404）
     */
    @Transactional
    public JobFavoriteEntity setPreference(String userId, Long jobId, String preference) {
        String normalized = normalizePreference(preference);
        JobFavoriteEntity entity = jobFavoriteRepository.findByUserIdAndJobId(userId, jobId).orElse(null);
        if (entity == null) {
            return null;
        }
        entity.setPreference(normalized);
        return jobFavoriteRepository.save(entity);
    }

    /** 归一化偏好档位：null/空串→null（未标记）；大小写归一；非法值抛 400。 */
    private static String normalizePreference(String preference) {
        if (preference == null) {
            return null;
        }
        String p = preference.trim().toUpperCase();
        if (p.isEmpty()) {
            return null;
        }
        if (!ALL_PREFERENCES.contains(p)) {
            throw new IllegalArgumentException(
                    "preference 非法，合法取值：" + String.join("/", ALL_PREFERENCES) + "（null 表示清除标记）");
        }
        return p;
    }
}
