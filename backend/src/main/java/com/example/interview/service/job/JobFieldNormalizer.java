package com.example.interview.service.job;

/**
 * 招聘岗位学历/经验字段归一化（v1.27.0）
 *
 * 将第三方/种子数据的自由文本（如「本科及以上」「硕士在读」「2027 届应届毕业生」「1-3 年」）
 * 归一到统一的精选枚举，使筛选面板的 degree/experience 去重列表收敛、精确筛选结果更有意义。
 * 纯确定性规则（不依赖模型），保证筛选一致性；无法识别一律归为「不限」。
 */
public final class JobFieldNormalizer {

    private JobFieldNormalizer() {
    }

    /** 学历：博士 / 硕士 / 本科 / 大专 / 不限 */
    public static String normalizeDegree(String raw) {
        if (raw == null || raw.isBlank()) {
            return "不限";
        }
        String s = raw.trim();
        if (s.contains("博士")) return "博士";
        if (s.contains("硕士")) return "硕士";
        if (s.contains("本科")) return "本科";
        if (s.contains("大专")) return "大专";
        return "不限";
    }

    /** 经验：在校生 / 应届生 / 1-3年 / 3-5年 / 5年以上 / 不限 */
    public static String normalizeExperience(String raw) {
        if (raw == null || raw.isBlank()) {
            return "不限";
        }
        String s = raw.trim();
        if (s.contains("在校")) return "在校生";
        if (s.contains("应届") || s.contains("毕业") || s.contains("届")) return "应届生";
        if (s.contains("不限")) return "不限";
        if (s.contains("1-3") || s.contains("1 - 3") || s.contains("1~3")) return "1-3年";
        if (s.contains("3-5") || s.contains("3 - 5") || s.contains("3~5")) return "3-5年";
        if (s.contains("5年以上") || s.contains("5 年以上") || s.contains("5年")) return "5年以上";
        return "不限";
    }
}