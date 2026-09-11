package com.example.interview.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 岗位实体测试（v1.31.3）：验证 @Builder.Default 使 active 默认值在 builder 下生效，
 * 防止回归 A-02（此前 @Builder 忽略 active=true 初始化导致 active 为 null）。
 */
class JobPostingEntityTest {

    @Test
    @DisplayName("builder 构建且未显式设置 active 时，默认 active=true 生效")
    void builderDefault_activeIsTrueWhenNotSet() {
        JobPostingEntity job = JobPostingEntity.builder()
                .title("Java 开发")
                .companyName("腾讯")
                .externalId("ext-1")
                .build();
        assertThat(job).isNotNull();
        assertThat(job.getActive()).isEqualTo(Boolean.TRUE);
    }

    @Test
    @DisplayName("显式设置 active=false 时覆盖默认值")
    void builderExplicitActive_overridesDefault() {
        JobPostingEntity job = JobPostingEntity.builder()
                .title("Java 开发")
                .companyName("腾讯")
                .externalId("ext-2")
                .active(false)
                .build();
        assertThat(job.getActive()).isEqualTo(Boolean.FALSE);
    }
}