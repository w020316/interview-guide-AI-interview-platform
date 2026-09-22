package com.example.interview.service.job;

import com.example.interview.entity.JobApplicationEntity;
import com.example.interview.entity.JobFavoriteEntity;
import com.example.interview.entity.JobPostingEntity;
import com.example.interview.repository.JobApplicationRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 投递台账业务服务（v1.35.0）
 *
 * <p>设计来源：抖音「求职 agent 大更新 / BossHunter」的投递闭环思路，并按本项目合规边界收敛——
 * <b>只做本地台账、定制简历与状态提醒，不做自动登录/自动投递</b>。
 *
 * <p>状态机（单向推进，允许回退到 WITHDRAWN）：
 * <pre>
 *   PLANNED（待投递） --confirm--> APPLIED（已投递） --markStatus--> VIEWED / REPLIED
 *        --> INTERVIEW --> OFFER
 *        --> REJECTED（淘汰）
 * </pre>
 *
 * 回复监测为 read-time 计算：{@link #board(String)} 依据 {@code nextActionAt} 与
 * 状态停留时长推导「待跟进」列表，无需后台定时任务。
 */
@Service
public class JobApplicationService {

    private static final Logger log = LoggerFactory.getLogger(JobApplicationService.class);

    /** 全部合法状态（顺序即看板列顺序） */
    public static final List<String> ALL_STATUSES = List.of(
            JobApplicationEntity.STATUS_PLANNED,
            JobApplicationEntity.STATUS_APPLIED,
            JobApplicationEntity.STATUS_VIEWED,
            JobApplicationEntity.STATUS_REPLIED,
            JobApplicationEntity.STATUS_INTERVIEW,
            JobApplicationEntity.STATUS_OFFER,
            JobApplicationEntity.STATUS_REJECTED,
            JobApplicationEntity.STATUS_WITHDRAWN);

    /** 状态中文标签（看板与智能体输出复用） */
    public static final Map<String, String> STATUS_LABELS = Map.of(
            JobApplicationEntity.STATUS_PLANNED, "待投递",
            JobApplicationEntity.STATUS_APPLIED, "已投递",
            JobApplicationEntity.STATUS_VIEWED, "已查看",
            JobApplicationEntity.STATUS_REPLIED, "有回复",
            JobApplicationEntity.STATUS_INTERVIEW, "面试中",
            JobApplicationEntity.STATUS_OFFER, "已拿 Offer",
            JobApplicationEntity.STATUS_REJECTED, "已淘汰",
            JobApplicationEntity.STATUS_WITHDRAWN, "已放弃");

    /** 「已投出、等待对方响应」的状态集合——回复监测的核心观察对象 */
    private static final Set<String> PENDING_REPLY_STATUSES = Set.of(
            JobApplicationEntity.STATUS_APPLIED,
            JobApplicationEntity.STATUS_VIEWED);

    /** 投出后多少天无任何状态变化即提示跟进（回复监测阈值） */
    private static final int FOLLOW_UP_SILENT_DAYS = 7;

    @Autowired
    private JobApplicationRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** 插入走独立新事务，撞唯一约束可在方法内捕获并幂等返回，不污染外层事务 */
    private TransactionTemplate insertTemplate;

    @PostConstruct
    void initInsertTemplate() {
        insertTemplate = new TransactionTemplate(transactionManager);
        insertTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 惰性获取独立事务模板。
     *
     * <p>@PostConstruct 在单元测试（未启动 Spring 容器）中不会执行，此处做惰性兜底，
     * 使插入路径在切片测试下同样可用；容器环境下等价于 {@link #initInsertTemplate()}。
     */
    private TransactionTemplate insertTemplate() {
        TransactionTemplate t = insertTemplate;
        if (t == null) {
            synchronized (this) {
                t = insertTemplate;
                if (t == null) {
                    t = new TransactionTemplate(transactionManager);
                    t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    insertTemplate = t;
                }
            }
        }
        return t;
    }

    /** 查询用户全部投递记录（最近更新倒序） */
    public List<JobApplicationEntity> listByUser(String userId) {
        return repository.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    /** 归属校验后按 ID 取记录，非本人返回 null（防 IDOR） */
    public JobApplicationEntity findOwned(String userId, Long id) {
        if (id == null) {
            return null;
        }
        return repository.findById(id)
                .filter(a -> userId.equals(a.getUserId()))
                .orElse(null);
    }

    /**
     * 从岗位库岗位加入投递台账（幂等）
     *
     * @return 已存在则返回原记录，不存在则新建 PLANNED 记录
     */
    @Transactional
    public JobApplicationEntity addFromJob(String userId, JobPostingEntity job, String note) {
        JobApplicationEntity existing = repository.findByUserIdAndJobId(userId, job.getId()).orElse(null);
        if (existing != null) {
            return existing;
        }
        JobApplicationEntity entity = JobApplicationEntity.builder()
                .userId(userId)
                .jobId(job.getId())
                .title(job.getTitle())
                .companyName(job.getCompanyName())
                .platform(job.getPlatform())
                .location(job.getLocation())
                .salary(job.getSalary())
                .deadline(job.getDeadline())
                .applyUrl(job.getApplyUrl())
                .status(JobApplicationEntity.STATUS_PLANNED)
                .note(note)
                .build();
        return insertIdempotent(entity, userId, job.getId());
    }

    /**
     * 从岗位收藏快照加入投递台账（幂等）
     *
     * <p>收藏记录本身就是岗位快照，可直接转入台账；jobId 为 null 的收藏
     * （理论上不存在，收藏时必带 jobId）按 0 处理，仍可正常建账。
     */
    @Transactional
    public JobApplicationEntity addFromFavorite(String userId, JobFavoriteEntity favorite, String note) {
        Long jobId = favorite.getJobId() == null ? 0L : favorite.getJobId();
        JobApplicationEntity existing = repository.findByUserIdAndJobId(userId, jobId).orElse(null);
        if (existing != null) {
            return existing;
        }
        JobApplicationEntity entity = JobApplicationEntity.builder()
                .userId(userId)
                .jobId(jobId)
                .title(favorite.getTitle())
                .companyName(favorite.getCompanyName())
                .platform(favorite.getPlatform())
                .location(favorite.getLocation())
                .salary(favorite.getSalary())
                .deadline(favorite.getDeadline())
                .applyUrl(favorite.getApplyUrl())
                .status(JobApplicationEntity.STATUS_PLANNED)
                .note(note)
                .build();
        return insertIdempotent(entity, userId, jobId);
    }

    /** 并发双击兜底：撞唯一约束时重查并按幂等语义返回 */
    private JobApplicationEntity insertIdempotent(JobApplicationEntity entity, String userId, Long jobId) {
        try {
            return insertTemplate().execute(status -> repository.saveAndFlush(entity));
        } catch (DataIntegrityViolationException e) {
            log.info("投递台账并发插入撞唯一约束，按幂等返回已有记录 userId={} jobId={}", userId, jobId);
            return repository.findByUserIdAndJobId(userId, jobId)
                    .orElseThrow(() -> e);
        }
    }

    /**
     * 人工确认投递：PLANNED → APPLIED，写入投出时间
     *
     * <p>平台不代替用户投递，此处仅表示「用户已自行前往 applyUrl 完成投递」。
     *
     * @return 更新后的记录；非本人或状态非法返回 null
     */
    @Transactional
    public JobApplicationEntity confirmApply(String userId, Long id) {
        JobApplicationEntity entity = findOwned(userId, id);
        if (entity == null) {
            return null;
        }
        entity.setStatus(JobApplicationEntity.STATUS_APPLIED);
        if (entity.getAppliedAt() == null) {
            entity.setAppliedAt(LocalDateTime.now());
        }
        entity.setLastReplyAt(LocalDateTime.now());
        return repository.save(entity);
    }

    /**
     * 记录回复/推进状态（回复监测）
     *
     * @param status 目标状态，必须在 {@link #ALL_STATUSES} 内
     * @param note   可选备注（沟通记录/面试反馈），非空则覆盖
     * @return 更新后的记录；非本人或状态非法返回 null
     */
    @Transactional
    public JobApplicationEntity updateStatus(String userId, Long id, String status, String note,
                                             LocalDateTime nextActionAt) {
        if (status == null || !ALL_STATUSES.contains(status.toUpperCase())) {
            return null;
        }
        JobApplicationEntity entity = findOwned(userId, id);
        if (entity == null) {
            return null;
        }
        entity.setStatus(status.toUpperCase());
        entity.setLastReplyAt(LocalDateTime.now());
        if (note != null && !note.isBlank()) {
            entity.setNote(note.trim());
        }
        if (nextActionAt != null) {
            entity.setNextActionAt(nextActionAt);
        }
        // 从未投递直接跳到「已投递及以后」时补记投出时间，保证统计口径正确
        if (entity.getAppliedAt() == null
                && !JobApplicationEntity.STATUS_PLANNED.equals(entity.getStatus())) {
            entity.setAppliedAt(LocalDateTime.now());
        }
        return repository.save(entity);
    }

    /** 保存针对该岗位生成的定制简历要点（AI 产出） */
    @Transactional
    public JobApplicationEntity saveTailoredResume(String userId, Long id, String tailoredResume) {
        JobApplicationEntity entity = findOwned(userId, id);
        if (entity == null) {
            return null;
        }
        entity.setTailoredResume(tailoredResume);
        return repository.save(entity);
    }

    /** 从台账移除（放弃追踪，不影响用户在招聘平台的实际投递） */
    @Transactional
    public boolean remove(String userId, Long id) {
        JobApplicationEntity entity = findOwned(userId, id);
        if (entity == null) {
            return false;
        }
        repository.delete(entity);
        return true;
    }

    /**
     * 投递看板：分列计数 + 转化漏斗 + 待跟进提醒
     *
     * <p>「待跟进」判定（read-time，无定时任务）：
     * <ol>
     *   <li>显式设置了 {@code nextActionAt} 且已到期；</li>
     *   <li>处于「已投递/已查看」且距投出已超过 {@value #FOLLOW_UP_SILENT_DAYS} 天仍无回复。</li>
     * </ol>
     */
    public Map<String, Object> board(String userId) {
        List<JobApplicationEntity> all = listByUser(userId);

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String s : ALL_STATUSES) {
            counts.put(s, 0);
        }
        List<JobApplicationEntity> followUps = new java.util.ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (JobApplicationEntity a : all) {
            counts.merge(a.getStatus() == null ? JobApplicationEntity.STATUS_PLANNED : a.getStatus(), 1, Integer::sum);
            if (needsFollowUp(a, now)) {
                followUps.add(a);
            }
        }

        int applied = countFrom(counts, JobApplicationEntity.STATUS_APPLIED);
        int viewed = countFrom(counts, JobApplicationEntity.STATUS_VIEWED);
        int replied = countFrom(counts, JobApplicationEntity.STATUS_REPLIED);
        int interview = countFrom(counts, JobApplicationEntity.STATUS_INTERVIEW);
        int offer = countFrom(counts, JobApplicationEntity.STATUS_OFFER);
        int rejected = countFrom(counts, JobApplicationEntity.STATUS_REJECTED);
        int submitted = applied + viewed + replied + interview + offer + rejected;

        Map<String, Object> funnel = new LinkedHashMap<>();
        funnel.put("submitted", submitted);
        funnel.put("viewedOrBeyond", viewed + replied + interview + offer + rejected);
        funnel.put("repliedOrBeyond", replied + interview + offer);
        funnel.put("interviewOrBeyond", interview + offer);
        funnel.put("offer", offer);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", all.size());
        result.put("counts", counts);
        result.put("statusLabels", STATUS_LABELS);
        result.put("funnel", funnel);
        result.put("followUpCount", followUps.size());
        result.put("followUps", followUps.stream().limit(10).toList());
        return result;
    }

    /** 是否需要跟进（回复监测核心判定） */
    public boolean needsFollowUp(JobApplicationEntity a, LocalDateTime now) {
        if (a.getNextActionAt() != null && !a.getNextActionAt().isAfter(now)) {
            return true;
        }
        if (a.getStatus() != null && PENDING_REPLY_STATUSES.contains(a.getStatus())) {
            LocalDateTime base = a.getAppliedAt() != null ? a.getAppliedAt() : a.getUpdatedAt();
            return base != null && base.isBefore(now.minusDays(FOLLOW_UP_SILENT_DAYS));
        }
        return false;
    }

    /** 供智能体/接口复用的中文状态标签 */
    public static String label(String status) {
        if (status == null) {
            return "未知";
        }
        return STATUS_LABELS.getOrDefault(status, status);
    }

    private static int countFrom(Map<String, Integer> counts, String status) {
        return counts.getOrDefault(status, 0);
    }
}
