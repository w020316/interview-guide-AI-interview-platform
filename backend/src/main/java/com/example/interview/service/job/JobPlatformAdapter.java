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
            String recruitType,   // AUTUMN / SPRING / SOCIAL / INTERN
            LocalDate deadline,
            String applyUrl,
            String description,
            String requirements,
            String tags
    ) {
    }
}
