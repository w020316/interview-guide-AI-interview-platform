package com.example.interview.util;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 简历总分聚合器（v1.72.0，2026-10-18）。
 *
 * <p><b>为什么要有这个类</b>：扩展评测（`07-AI评测-扩展样本.md` §4）发现，
 * 让模型单独给 `overallScore` 会导致总分**不随内容变化**——
 * 同一份简历分别「删掉全部量化数字」「把主导降级为参与」「技术栈模糊化」，
 * 三个变体总分全是 85，一分没动；反而给弱简历**补上**量化数字后总分从 75 掉到 70。
 *
 * <p>根因是总分是一个独立的、不可解释的生成值：模型判"这是一份不错且对口的简历"
 * 就给个整数，具体内容改了什么它不再重算。表现到用户侧就是「改好了不加分」——
 * 而这个产品的核心承诺恰恰是「改完再分析，看分数变了没」。
 *
 * <p>所以改为**由维度分确定性聚合**：总分 = Σ(维度分 × 权重) / Σ权重。
 * 好处：可解释（用户看得出哪个维度拖后腿）、可单测（纯函数）、与内容严格联动。
 *
 * <p>权重选择（0.4 / 0.3 / 0.2 / 0.1）：
 * 「含金量」权重最高，因为它是区分候选人的主要变量；「求职意向匹配度」最低，
 * 因为实测该维度几乎人人 70–90、区分度很低（10 份样本里从未低于 70）。
 * 所有权重集中在本类常量里，要调只改一处。
 */
public final class ScoreAggregator {

    private ScoreAggregator() {
    }

    /** 维度关键词 → 权重。用「包含」匹配而非全等，容忍模型措辞漂移（如「项目经历含金量」少了「工作」两个字）。 */
    private static final Map<String, Double> WEIGHTS = new LinkedHashMap<>();

    static {
        WEIGHTS.put("含金量", 0.40);
        WEIGHTS.put("岗位匹配", 0.30);
        WEIGHTS.put("清晰", 0.20);
        WEIGHTS.put("意向", 0.10);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 把 JSON 结果里的 `overallScore` 替换为按维度确定性聚合的值。
     *
     * <p>任何一步失败（不是 JSON、没有维度、全是无法识别的维度名）都**原样返回**，
     * 保留模型给的值 —— 宁可沿用不可解释的旧值，也不要因为聚合失败把分数写坏。
     *
     * @param json 已通过契约校验的分析结果 JSON
     * @return 重算过总分的 JSON（失败时原样返回入参）
     */
    public static String applyDeterministicOverall(String json) {
        if (json == null || json.isBlank()) {
            return json;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = MAPPER.readValue(json, Map.class);
            Object dimsObj = root.get("dimensions");
            if (!(dimsObj instanceof List<?> dims) || dims.isEmpty()) {
                return json;
            }

            Integer computed = computeOverall(dims);
            if (computed == null) {
                return json;
            }
            root.put("overallScore", computed);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return json;
        }
    }

    /**
     * 纯计算：Σ(维度分 × 权重) / Σ(命中权重)。
     *
     * <p>只对**能匹配到权重关键词**的维度计权；未识别的维度不计权也不惩罚。
     * 若一个维度都没匹配上，返回 {@code null}（调用方保留原值）。
     */
    static Integer computeOverall(List<?> dimensions) {
        double weightedSum = 0;
        double weightSum = 0;

        for (Object o : dimensions) {
            if (!(o instanceof Map<?, ?> dim)) {
                continue;
            }
            Object nameObj = dim.get("name");
            Object scoreObj = dim.get("score");
            if (!(nameObj instanceof String name) || !(scoreObj instanceof Number score)) {
                continue;
            }
            Double w = weightOf(name);
            if (w == null) {
                continue;
            }
            // 越界分数先钳住：模型偶尔给 -5 或 120，直接参与加权会把总分带飞
            double s = Math.max(0, Math.min(100, score.doubleValue()));
            weightedSum += s * w;
            weightSum += w;
        }

        if (weightSum <= 0) {
            return null;
        }
        long rounded = Math.round(weightedSum / weightSum);
        return (int) Math.max(0, Math.min(100, rounded));
    }

    private static Double weightOf(String name) {
        for (Map.Entry<String, Double> e : WEIGHTS.entrySet()) {
            if (name.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }
}
