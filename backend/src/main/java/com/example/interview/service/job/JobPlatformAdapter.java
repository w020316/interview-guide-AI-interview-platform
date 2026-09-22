package com.example.interview.service.job;

import java.time.LocalDate;

/**
 * 招聘平台适配器接口（招聘信息智能体）
 *
 * 每个数据源（内置精选/智联招聘/前程无忧/BOSS直聘等）实现本接口，
 * 由 JobAgentService 统一调度拉取并幂等入库。
 */
public interface JobPlatformAdapter {

    /**
     * 平台标识（与 entity.platform 对应），如：内置精选 / 智联招聘 / 前程无忧 / BOSS直聘
     */
    String platform();

    /**
     * 该适配器当前是否可用（如第三方数据服务未配置 endpoint 时返回 false，调度时跳过）
     */
    default boolean isEnabled() {
        return true;
    }

    /**
     * 是否为海外 / 远程岗位数据源（v1.38.0）
     *
     * <p>招聘广场据此实现「海外远程」独立分栏：岗位仍与国内数据同库同表，
     * 但可按来源类别一键隔离——避免英文海外岗位混进国内校招/社招列表，
     * 也避免国内求职者按「秋招」筛选时被海外岗位稀释。
     *
     * <p>由适配器自己声明而不是在查询层硬编码平台名：新增海外源时无需改动查询逻辑。
     */
    default boolean overseas() {
        return false;
    }

    /**
     * 该数据源的最小刷新间隔（毫秒），0 表示每轮刷新都拉取。
     *
     * <p>用途：公开第三方 API 通常有未公开的频率限制与礼貌性要求，
     * 没必要像内置种子那样每小时拉一次；内置数据源每轮都刷新（幂等 upsert，开销极低）。
     */
    default long minRefreshIntervalMs() {
        return 0L;
    }

    /**
     * 拉取岗位列表。实现需保证幂等：externalId 稳定不变，同一岗位重复拉取内容一致。
     * 抛异常只影响该平台，不影响其他平台数据。
     */
    java.util.List<JobDto> fetch();

    /**
     * 标准化岗位数据传输对象
     */
    record JobDto(
            String externalId,
            String title,
            String companyName,
            String industry,
            String jobType,
            String location,
            String salary,
            String degree,
            String experience,
            String recruitType,   // AUTUMN 秋招 / SPRING 春招 / SOCIAL 社招 / INTERN 实习 / TARGETED 定向专项
            LocalDate deadline,
            String applyUrl,
            String description,
            String requirements,
            String tags
    ) {
    }
}
