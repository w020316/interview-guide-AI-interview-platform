package com.example.interview.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PaginationSupport} 测试。
 *
 * <p>回归背景（2026-09-26 第三轮 UX 测试 P3-04）：线上实测
 * {@code GET /api/jobs?size=-5} 返回 {@code code=200} 且 {@code size=1} ——
 * 服务层钳制是 {@code Math.max(1, size)}，于是「要 -5 条」静默变成「1 条」，
 * 调用方的参数错误永远暴露不出来。
 */
@DisplayName("PaginationSupport 分页参数契约")
class PaginationSupportTest {

    @Test
    @DisplayName("size < 1 被拒绝（0 与负数）")
    void rejectsNonPositive() {
        assertThat(PaginationSupport.validateSize(0)).isNotNull();
        assertThat(PaginationSupport.validateSize(-1)).isNotNull();
        assertThat(PaginationSupport.validateSize(-100)).isNotNull();
    }

    @Test
    @DisplayName("size ≥ 1 放行；超过上限不在这里拦（由服务层钳制）")
    void acceptsPositive() {
        assertThat(PaginationSupport.validateSize(1)).isNull();
        assertThat(PaginationSupport.validateSize(PaginationSupport.DEFAULT_SIZE)).isNull();
        assertThat(PaginationSupport.validateSize(99999)).isNull();
    }

    @Test
    @DisplayName("提示文案面向用户、可读")
    void messageIsUserFacing() {
        String msg = PaginationSupport.validateSize(-5);
        assertThat(msg).contains("size").doesNotContain("Exception", "null", "正则");
    }

    @Test
    @DisplayName("常量与接口声明保持一致")
    void constantsMatchContract() {
        assertThat(PaginationSupport.DEFAULT_SIZE).isEqualTo(10);
        assertThat(PaginationSupport.MAX_SIZE).isEqualTo(50);
    }
}
