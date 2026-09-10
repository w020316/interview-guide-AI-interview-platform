package com.example.interview.service.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 招聘信息定时刷新调度器
 *
 * 默认每 6 小时拉取一次各平台岗位（app.job-agent.refresh-fixed-delay-ms 可调），
 * 启动后 60 秒执行首次刷新，保证服务启动后即可查询到岗位数据。
 * 单次刷新失败仅记录日志，不影响下一轮调度。
 */
@Component
public class JobRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobRefreshScheduler.class);

    private final JobAgentService jobAgentService;

    /** 防止上一轮刷新未结束时重复触发 */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public JobRefreshScheduler(JobAgentService jobAgentService) {
        this.jobAgentService = jobAgentService;
    }

    /** 启动后 60s 首次刷新，之后按固定间隔刷新 */
    @Scheduled(initialDelay = 60_000, fixedDelayString = "${app.job-agent.refresh-fixed-delay-ms:21600000}")
    public void scheduledRefresh() {
        if (!running.compareAndSet(false, true)) {
            log.info("上一轮招聘信息刷新尚未结束，跳过本次调度");
            return;
        }
        try {
            JobAgentService.RefreshResult result = jobAgentService.refresh();
            log.info("定时刷新完成：{}", result);
        } catch (Exception e) {
            log.warn("定时刷新失败（不影响下一轮调度）：{}", e.getMessage());
        } finally {
            running.set(false);
        }
    }
}
