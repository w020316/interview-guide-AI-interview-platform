package com.example.interview.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PII 脱敏测试（P2-07）。
 *
 * <p>注意：本类只覆盖「格式明确的联系方式/证件号」；姓名/公司等自由文本
 * 不在正则能力范围内，测试里明确不承诺。
 */
@DisplayName("PII 脱敏测试")
class PiiMaskerTest {

    @Test
    @DisplayName("邮箱被替换为占位符")
    void masksEmail() {
        assertThat(PiiMasker.mask("请联系 zhang.san+job@example.com 沟通"))
                .isEqualTo("请联系 [邮箱] 沟通");
    }

    @Test
    @DisplayName("11 位手机号被替换，且不误伤更长的数字串")
    void masksMobile() {
        assertThat(PiiMasker.mask("我的电话 13812345678，随时联系"))
                .isEqualTo("我的电话 [手机号]，随时联系");
        // 18 位数字是身份证，不能被手机号规则切掉一部分
        assertThat(PiiMasker.mask("证件 110101199003071234"))
                .isEqualTo("证件 [身份证号]");
    }

    @Test
    @DisplayName("身份证号（末位 X）同样被替换")
    void masksIdCardWithX() {
        assertThat(PiiMasker.mask("身份证 11010119900307123X"))
                .isEqualTo("身份证 [身份证号]");
    }

    @Test
    @DisplayName("不含 PII 的普通技术问题保持原样")
    void keepsPlainQuestion() {
        String q = "Redis 持久化有哪些方式？RDB 和 AOF 的区别是什么？";
        assertThat(PiiMasker.mask(q)).isEqualTo(q);
    }

    @Test
    @DisplayName("null / 空串原样返回")
    void handlesNullAndEmpty() {
        assertThat(PiiMasker.mask(null)).isNull();
        assertThat(PiiMasker.mask("")).isEmpty();
    }
}
