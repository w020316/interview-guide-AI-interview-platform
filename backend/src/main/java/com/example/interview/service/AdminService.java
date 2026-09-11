package com.example.interview.service;

import com.example.interview.entity.JobPostingEntity;
import com.example.interview.entity.UserEntity;
import com.example.interview.repository.JobPostingRepository;
import com.example.interview.repository.UserRepository;
import com.example.interview.service.job.JobAgentService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理后台服务（v1.31.4，仅 ROLE_ADMIN 可访问）
 * - 数据总览 + 手动刷新（复用 JobAgentService）
 * - 岗位数据管理：分页检索全部（含失效）、下架/恢复/删除
 * - 用户管理：分页列表（含禁用状态）、禁用/解禁
 * - 系统指标：AI 调用统计、SSE 并发水位
 */
@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    private final JobPostingRepository jobPostingRepository;
    private final UserRepository userRepository;
    private final JobAgentService jobAgentService;
    private final UserBanRegistry userBanRegistry;
    private final MeterRegistry meterRegistry;

    public AdminService(JobPostingRepository jobPostingRepository,
                        UserRepository userRepository,
                        JobAgentService jobAgentService,
                        UserBanRegistry userBanRegistry,
                        MeterRegistry meterRegistry) {
        this.jobPostingRepository = jobPostingRepository;
        this.userRepository = userRepository;
        this.jobAgentService = jobAgentService;
        this.userBanRegistry = userBanRegistry;
        this.meterRegistry = meterRegistry;
    }

    // ── 数据总览 ──

    /** 总览：岗位总量/有效量/失效量、用户总量、最近刷新时间、刷新中状态 */
    public Map<String, Object> overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        long totalJobs = jobPostingRepository.count();
        long activeJobs = jobPostingRepository.countByActiveTrue();
        result.put("totalJobs", totalJobs);
        result.put("activeJobs", activeJobs);
        result.put("inactiveJobs", totalJobs - activeJobs);
        result.put("totalUsers", userRepository.count());
        result.put("bannedUsers", userBanRegistry.bannedSnapshot().size());
        result.put("lastRefreshedAt", jobPostingRepository.findLastUpdatedAt());
        result.put("refreshing", jobAgentService.isRefreshing());
        return result;
    }

    /** 手动刷新岗位数据（管理员调用，无按用户限流） */
    public JobAgentService.RefreshResult refreshJobs() {
        JobAgentService.RefreshResult result = jobAgentService.refresh();
        if (result == null) {
            throw new IllegalStateException("岗位数据正在刷新中，请稍后再试");
        }
        return result;
    }

    // ── 岗位数据管理 ──

    /** 全部岗位分页（含失效；keyword 模糊匹配标题/公司/标签） */
    public Page<JobPostingEntity> listJobs(String keyword, int page, int size) {
        var spec = (org.springframework.data.jpa.domain.Specification<JobPostingEntity>) (root, query, cb) -> cb.conjunction();
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim().toLowerCase();
            String pattern = "%" + kw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("title")), pattern, '\\'),
                    cb.like(cb.lower(root.get("companyName")), pattern, '\\'),
                    cb.like(cb.lower(root.get("tags")), pattern, '\\')));
        }
        var pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 50));
        return jobPostingRepository.findAll(spec, pageable);
    }

    /** 下架岗位（active=false） */
    public void deactivateJob(Long id) {
        JobPostingEntity job = jobPostingRepository.findById(id).orElse(null);
        if (job == null) {
            throw new IllegalArgumentException("岗位不存在");
        }
        job.setActive(false);
        jobPostingRepository.save(job);
    }

    /** 恢复岗位（active=true） */
    public void activateJob(Long id) {
        JobPostingEntity job = jobPostingRepository.findById(id).orElse(null);
        if (job == null) {
            throw new IllegalArgumentException("岗位不存在");
        }
        job.setActive(true);
        jobPostingRepository.save(job);
    }

    /** 删除岗位 */
    public void deleteJob(Long id) {
        if (!jobPostingRepository.existsById(id)) {
            throw new IllegalArgumentException("岗位不存在");
        }
        jobPostingRepository.deleteById(id);
    }

    // ── 用户管理 ──

    /** 用户分页列表（脱敏：不含 passwordHash） */
    public Page<UserView> listUsers(String keyword, int page, int size) {
        var spec = (org.springframework.data.jpa.domain.Specification<UserEntity>) (root, query, cb) -> cb.conjunction();
        if (keyword != null && !keyword.isBlank()) {
            String kw = keyword.trim().toLowerCase();
            String pattern = "%" + kw + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("username")), pattern),
                    cb.like(cb.lower(root.get("email")), pattern)));
        }
        var pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 50),
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Order.desc("id")));
        Page<UserEntity> entities = userRepository.findAll(spec, pageable);
        return entities.map(u -> new UserView(
                u.getId(), u.getUsername(), u.getEmail(), u.getCreatedAt(),
                userBanRegistry.isBanned(u.getId())));
    }

    /** 用户视图 DTO（不暴露 passwordHash） */
    public record UserView(Long id, String username, String email, LocalDateTime createdAt, boolean banned) {
    }

    /** 禁用用户（进程内，重启失效） */
    public void banUser(Long id) {
        requireUser(id);
        userBanRegistry.ban(id);
        log.info("管理员禁用用户 id={}", id);
    }

    /** 解禁用户 */
    public void unbanUser(Long id) {
        requireUser(id);
        userBanRegistry.unban(id);
        log.info("管理员解禁用户 id={}", id);
    }

    private void requireUser(Long id) {
        if (id == null || !userRepository.existsById(id)) {
            throw new IllegalArgumentException("用户不存在");
        }
    }

    // ── 系统指标 ──

    /** 系统指标：AI 调用计数/耗时、缓存命中、SSE 水位、JVM 内存 */
    public Map<String, Object> metrics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("aiCalls", aiCallSummary());
        result.put("cache", cacheSummary());
        result.put("sse", sseSummary());
        result.put("jvm", jvmSummary());
        return result;
    }

    private Map<String, Object> aiCallSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String type : List.of("resume", "question", "evaluate", "jobAnalysis", "rag")) {
            var counter = meterRegistry.find("ai.call.count").tag("type", type).counter();
            summary.put(type, counter == null ? 0L : (long) counter.count());
        }
        var timer = meterRegistry.find("ai.call.duration").timer();
        if (timer != null) {
            summary.put("totalCalls", timer.count());
            summary.put("avgMs", timer.mean(java.util.concurrent.TimeUnit.MILLISECONDS));
            summary.put("p95Ms", timer.percentile(0.95, java.util.concurrent.TimeUnit.MILLISECONDS));
        }
        return summary;
    }

    private Map<String, Object> cacheSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        var hit = meterRegistry.find("cache.hit.count").counter();
        var miss = meterRegistry.find("cache.miss.count").counter();
        summary.put("hits", hit == null ? 0L : (long) hit.count());
        summary.put("misses", miss == null ? 0L : (long) miss.count());
        return summary;
    }

    private Map<String, Object> sseSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        var max = meterRegistry.find("sse.max.concurrent").gauge();
        var active = meterRegistry.find("sse.active.count").gauge();
        summary.put("maxConcurrent", max == null ? 20 : (int) max.value());
        summary.put("activeCount", active == null ? 0 : (int) active.value());
        return summary;
    }

    private Map<String, Object> jvmSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        Runtime rt = Runtime.getRuntime();
        summary.put("usedMb", (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024);
        summary.put("freeMb", rt.freeMemory() / 1024 / 1024);
        summary.put("totalMb", rt.totalMemory() / 1024 / 1024);
        summary.put("maxMb", rt.maxMemory() / 1024 / 1024);
        return summary;
    }
}