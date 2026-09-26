package com.example.interview.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PasswordPolicy} 测试。
 *
 * <p>回归背景（2026-09-26 第三轮 UX 测试 P3-02）：线上实测
 * {@code POST /api/auth/register} 用 {@code password=12345678} 返回 {@code code=200 success}
 * —— 此前只校验长度 6~64，常见弱口令一律放行。
 *
 * <p>同时锁定「不过度拦截」：长口令、含空格的口令、以及形如 {@code a1b2c3} 的
 * 非连续串都必须放行，避免把规则做成「逼用户把密码写在便签上」的复杂度检查。
 */
@DisplayName("PasswordPolicy 弱口令策略")
class PasswordPolicyTest {

    @Test
    @DisplayName("常见弱口令被拒绝（含线上实测放行过的 12345678）")
    void rejectsCommonWeak() {
        for (String weak : new String[]{"123456", "12345678", "123456789", "password",
                "Password", "qwerty", "abc123", "iloveyou", "woaini1314", "5201314"}) {
            assertThat(PasswordPolicy.validate(weak, "alice"))
                    .as("弱口令 %s 应被拒绝", weak)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("整串同一字符被拒绝")
    void rejectsRepeatedChar() {
        assertThat(PasswordPolicy.validate("1111111", "alice")).isNotNull();
        assertThat(PasswordPolicy.validate("aaaaaaaa", "alice")).isNotNull();
    }

    @Test
    @DisplayName("连续序列被拒绝（正向与反向）")
    void rejectsSequential() {
        assertThat(PasswordPolicy.validate("1234567", "alice")).isNotNull();
        assertThat(PasswordPolicy.validate("7654321", "alice")).isNotNull();
        assertThat(PasswordPolicy.validate("abcdefg", "alice")).isNotNull();
        assertThat(PasswordPolicy.validate("gfedcba", "alice")).isNotNull();
    }

    @Test
    @DisplayName("包含用户名（长度 ≥3）被拒绝")
    void rejectsContainsUsername() {
        assertThat(PasswordPolicy.validate("alice2026!", "alice")).isNotNull();
        assertThat(PasswordPolicy.validate("xAlice9x", "alice")).isNotNull();
        // 用户名太短（<3）不做包含判断，否则「ab」这种会把大量正常密码误杀
        assertThat(PasswordPolicy.validate("ab2026!", "ab")).isNull();
    }

    @Test
    @DisplayName("正常密码放行：长口令、含空格、字母数字交替、带符号")
    void acceptsReasonable() {
        assertThat(PasswordPolicy.validate("StrongPw#2026", "alice")).isNull();
        assertThat(PasswordPolicy.validate("correct horse battery", "alice")).isNull();
        assertThat(PasswordPolicy.validate("a1b2c3d4", "alice")).isNull();
        assertThat(PasswordPolicy.validate("Ux3@Test2026", "ux3_user")).isNull();
        assertThat(PasswordPolicy.validate("张三的密码2026", "zhangsan")).isNull();
    }

    @Test
    @DisplayName("a1b2c3 这类「字母数字交替」不判为连续序列（避免误杀）")
    void doesNotFlagMixedAlternating() {
        assertThat(PasswordPolicy.validate("a1b2c3", "alice")).isNull();
        assertThat(PasswordPolicy.validate("z9y8x7", "alice")).isNull();
    }

    @Test
    @DisplayName("空密码返回长度提示，不抛异常")
    void handlesEmpty() {
        assertThat(PasswordPolicy.validate(null, "alice")).isNotNull();
        assertThat(PasswordPolicy.validate("", "alice")).isNotNull();
    }

    @Test
    @DisplayName("返回的文案面向用户、不含技术术语")
    void messagesAreUserFacing() {
        for (String weak : new String[]{"123456", "111111", "abcdef", "alice12345"}) {
            String msg = PasswordPolicy.validate(weak, "alice");
            assertThat(msg).isNotNull().doesNotContain("regex", "正则", "Exception", "null");
        }
    }
}
