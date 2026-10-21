package com.example.interview.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UserEntity#getTokenVersion()} 的构建默认值测试（P1-2，2026-10-21）。
 *
 * <h2>这个用例钉的是一个很难定位的坑</h2>
 *
 * <p>{@code UserEntity} 用了 Lombok {@code @Builder}，而 <b>{@code @Builder} 不会应用
 * 字段初始值</b> —— 只有 {@code @Builder.Default} 才会。漏掉注解时：
 *
 * <ol>
 *   <li>源码看起来是 {@code private Integer tokenVersion = 1;}，读代码的人会以为默认值是 1；</li>
 *   <li>{@code UserEntity.builder().build().getTokenVersion()} 实际返回 {@code null}；</li>
 *   <li>插入时撞 {@code token_version INT NOT NULL} 约束，报错是
 *       “null value in column token_version violates not-null constraint”，
 *       与真实原因（Lombok 注解缺失）毫无字面关联，排查会绕很远。</li>
 * </ol>
 *
 * <p>因此这里用「通过 builder 构造」而非「直接 new」来断言 —— 后者会走字段初始化，
 * 根本无法暴露这个问题。
 */
@DisplayName("UserEntity.tokenVersion 构建默认值（@Builder.Default 回归）")
class UserEntityTokenVersionTest {

    @Test
    @DisplayName("builder 构造的对象 tokenVersion 必须为 1（漏 @Builder.Default 时这里会拿到 null）")
    void builderAppliesDefaultTokenVersion() {
        UserEntity entity = UserEntity.builder()
                .id(1L)
                .username("alice")
                .passwordHash("hash")
                .build();

        assertThat(entity.getTokenVersion())
                .as("漏 @Builder.Default 会让 builder 构造的实体 tokenVersion=null，"
                        + "插入时撞 NOT NULL 约束且报错与真实原因无关")
                .isNotNull()
                .isEqualTo(1);
    }

    @Test
    @DisplayName("显式指定版本时 builder 不覆盖（默认值只兜底）")
    void builderRespectsExplicitVersion() {
        UserEntity entity = UserEntity.builder()
                .id(2L)
                .username("bob")
                .passwordHash("hash")
                .tokenVersion(7)
                .build();

        assertThat(entity.getTokenVersion()).isEqualTo(7);
    }

    @Test
    @DisplayName("无参构造也给出 1（JPA 反序列化路径的兜底）")
    void noArgsConstructorUsesDefault() {
        assertThat(new UserEntity().getTokenVersion()).isEqualTo(1);
    }
}
