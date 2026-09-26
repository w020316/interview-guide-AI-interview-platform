package com.example.interview.service.job;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RecruitType} 测试。
 *
 * <p>回归背景（2026-09-26 第三轮 P3-03）：线上实测 {@code GET /api/jobs?recruitType=ZZZ}
 * 返回 {@code code=200} 且 {@code total=0} —— 非法取值被当成「没有匹配」，
 * 用户会以为「真的没有岗位」。
 */
@DisplayName("RecruitType 招聘类型取值")
class RecruitTypeTest {

    @Test
    @DisplayName("六个规范取值齐全，且与前端 recruitTabs 的真实取值一致")
    void hasAllCanonicalCodes() {
        // ⚠️ 前端 JobsView.vue 的 recruitTabs 里除这 6 个真实值外，还有
        // FAVORITE（走收藏接口）与 OVERSEAS（转成 overseas=true）两个**虚拟值**，
        // 以及空串表示「全部国内」—— 它们不会作为 recruitType 发给 /api/jobs，
        // 因此不应出现在本枚举里。
        assertThat(RecruitType.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder(
                        "AUTUMN", "SPRING", "SOCIAL", "INTERN", "PART_TIME", "TARGETED");
    }

    @Test
    @DisplayName("每个取值都有中文标签（提示文案要用）")
    void everyCodeHasLabel() {
        for (RecruitType t : RecruitType.values()) {
            assertThat(t.label()).isNotBlank();
        }
        assertThat(RecruitType.AUTUMN.label()).isEqualTo("秋招");
        assertThat(RecruitType.PART_TIME.label()).isEqualTo("兼职");
    }

    @Test
    @DisplayName("解析大小写不敏感、忽略首尾空白")
    void fromCodeIsLenient() {
        assertThat(RecruitType.fromCode("AUTUMN")).isEqualTo(RecruitType.AUTUMN);
        assertThat(RecruitType.fromCode("autumn")).isEqualTo(RecruitType.AUTUMN);
        assertThat(RecruitType.fromCode("  Social  ")).isEqualTo(RecruitType.SOCIAL);
    }

    @Test
    @DisplayName("未知值/空值返回 null（由调用方决定宽容还是拒绝）")
    void fromCodeReturnsNullForUnknown() {
        assertThat(RecruitType.fromCode("ZZZ")).isNull();
        assertThat(RecruitType.fromCode("")).isNull();
        assertThat(RecruitType.fromCode("   ")).isNull();
        assertThat(RecruitType.fromCode(null)).isNull();
        // 虚拟值不该被当成合法招聘类型
        assertThat(RecruitType.fromCode("OVERSEAS")).isNull();
        assertThat(RecruitType.fromCode("FAVORITE")).isNull();
    }

    @Test
    @DisplayName("校验：合法值与空值放行（空值 = 不限，前端「全部国内」就发空串）")
    void validationAllowsValidAndBlank() {
        for (RecruitType t : RecruitType.values()) {
            assertThat(RecruitType.validationError(t.name())).isNull();
        }
        assertThat(RecruitType.validationError(null)).isNull();
        assertThat(RecruitType.validationError("")).isNull();
        assertThat(RecruitType.validationError("   ")).isNull();
    }

    @Test
    @DisplayName("校验：非法值返回带可选清单的提示")
    void validationRejectsUnknown() {
        String msg = RecruitType.validationError("ZZZ");

        assertThat(msg).isNotNull().contains("招聘类型取值无效");
        // 提示里要能看出该填什么，而不是只说「无效」
        assertThat(msg).contains("秋招(AUTUMN)");
        assertThat(msg).contains("兼职(PART_TIME)");
    }

    @Test
    @DisplayName("可选清单覆盖全部取值")
    void allowedDescriptionCoversAll() {
        String desc = RecruitType.allowedDescription();

        for (RecruitType t : RecruitType.values()) {
            assertThat(desc).contains(t.name()).contains(t.label());
        }
    }
}
