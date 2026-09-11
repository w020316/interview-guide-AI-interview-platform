package com.example.interview.service.job;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 招聘岗位学历/经验字段归一化测试
 */
@DisplayName("招聘字段归一化测试")
class JobFieldNormalizerTest {

    @Test
    @DisplayName("学历归一化：硕士/本科/大专/博士/不限")
    void normalizeDegree() {
        assertThat(JobFieldNormalizer.normalizeDegree("本科")).isEqualTo("本科");
        assertThat(JobFieldNormalizer.normalizeDegree("本科及以上")).isEqualTo("本科");
        assertThat(JobFieldNormalizer.normalizeDegree("本科（统计/数学/CS）")).isEqualTo("本科");
        assertThat(JobFieldNormalizer.normalizeDegree("大专及以上")).isEqualTo("大专");
        assertThat(JobFieldNormalizer.normalizeDegree("硕士")).isEqualTo("硕士");
        assertThat(JobFieldNormalizer.normalizeDegree("硕士在读")).isEqualTo("硕士");
        assertThat(JobFieldNormalizer.normalizeDegree("硕士优先")).isEqualTo("硕士");
        assertThat(JobFieldNormalizer.normalizeDegree("硕士（优秀本科可）")).isEqualTo("硕士");
        assertThat(JobFieldNormalizer.normalizeDegree("博士")).isEqualTo("博士");
        assertThat(JobFieldNormalizer.normalizeDegree(null)).isEqualTo("不限");
        assertThat(JobFieldNormalizer.normalizeDegree("   ")).isEqualTo("不限");
    }

    @Test
    @DisplayName("经验归一化：在校生/应届生/分段/不限")
    void normalizeExperience() {
        assertThat(JobFieldNormalizer.normalizeExperience("在校生")).isEqualTo("在校生");
        assertThat(JobFieldNormalizer.normalizeExperience("2027 届应届毕业生")).isEqualTo("应届生");
        assertThat(JobFieldNormalizer.normalizeExperience("2027届")).isEqualTo("应届生");
        assertThat(JobFieldNormalizer.normalizeExperience("2026届")).isEqualTo("应届生");
        assertThat(JobFieldNormalizer.normalizeExperience("2026.1-2027.12毕业")).isEqualTo("应届生");
        assertThat(JobFieldNormalizer.normalizeExperience("2026-11 至 2027-10 毕业生")).isEqualTo("应届生");
        assertThat(JobFieldNormalizer.normalizeExperience("应届")).isEqualTo("应届生");
        assertThat(JobFieldNormalizer.normalizeExperience("1-3 年")).isEqualTo("1-3年");
        assertThat(JobFieldNormalizer.normalizeExperience("3-5 年")).isEqualTo("3-5年");
        assertThat(JobFieldNormalizer.normalizeExperience("5年以上")).isEqualTo("5年以上");
        assertThat(JobFieldNormalizer.normalizeExperience("经验不限")).isEqualTo("不限");
        assertThat(JobFieldNormalizer.normalizeExperience(null)).isEqualTo("不限");
    }
}