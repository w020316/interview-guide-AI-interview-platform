package com.example.interview.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ScoreAggregator} 单元测试（v1.72.0）。
 *
 * <p>这个类存在的唯一理由：让总分**随内容变化**。所以测试的重点不是"能不能算出个数"，
 * 而是 <b>总分是否严格由维度决定</b> —— 维度动一分，总分就要跟着动。
 */
@DisplayName("简历总分确定性聚合")
class ScoreAggregatorTest {

    private static Map<String, Object> dim(String name, Object score) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("score", score);
        return m;
    }

    @Test
    @DisplayName("四个标准维度按 0.4/0.3/0.2/0.1 加权")
    void computesWeightedAverage() {
        List<Map<String, Object>> dims = List.of(
                dim("岗位匹配度", 80),
                dim("项目/工作经历含金量", 90),
                dim("简历表述清晰度", 60),
                dim("求职意向匹配度", 50)
        );
        // 80*0.3 + 90*0.4 + 60*0.2 + 50*0.1 = 24 + 36 + 12 + 5 = 77
        assertThat(ScoreAggregator.computeOverall(dims)).isEqualTo(77);
    }

    @Test
    @DisplayName("核心性质：维度分变化必须带动总分（这正是旧实现做不到的）")
    void totalRespondsToDimensionChange() {
        List<Map<String, Object>> base = List.of(
                dim("岗位匹配度", 80), dim("项目/工作经历含金量", 90),
                dim("简历表述清晰度", 80), dim("求职意向匹配度", 80));
        List<Map<String, Object>> worse = List.of(
                dim("岗位匹配度", 80), dim("项目/工作经历含金量", 70),  // 含金量掉 20
                dim("简历表述清晰度", 80), dim("求职意向匹配度", 80));

        int a = ScoreAggregator.computeOverall(base);
        int b = ScoreAggregator.computeOverall(worse);
        // 80*0.3 + 90*0.4 + 80*0.2 + 80*0.1 = 24 + 36 + 16 + 8 = 84
        assertThat(a).isEqualTo(84);
        // 含金量 90→70，权重 0.4 → 总分应掉 8 分：84 - 8 = 76
        assertThat(b).isEqualTo(76);
        assertThat(a).isGreaterThan(b);
    }

    @Test
    @DisplayName("模型换了措辞也能匹配：「项目经历含金量」缺「工作」二字照样计权")
    void matchesByKeywordNotExactName() {
        List<Map<String, Object>> dims = List.of(
                dim("岗位匹配度", 80), dim("项目经历含金量", 60));
        // 80*0.3 + 60*0.4 = 48 ；权重和 0.7 → 48/0.7 ≈ 68.6 → 69
        assertThat(ScoreAggregator.computeOverall(dims)).isEqualTo(69);
    }

    @Test
    @DisplayName("缺失维度时按命中权重重归一，而不是把缺失维度当 0 分")
    void renormalizesWhenDimensionMissing() {
        // 只有清晰度 60（权重 0.2）→ 归一后仍是 60，绝不能被拉成 12
        assertThat(ScoreAggregator.computeOverall(List.of(dim("简历表述清晰度", 60)))).isEqualTo(60);
    }

    @Test
    @DisplayName("越界分数先钳到 0~100，避免 -5 / 120 把总分带飞")
    void clampsOutOfRangeScores() {
        assertThat(ScoreAggregator.computeOverall(List.of(dim("岗位匹配度", 120)))).isEqualTo(100);
        assertThat(ScoreAggregator.computeOverall(List.of(dim("岗位匹配度", -5)))).isEqualTo(0);
    }

    @Test
    @DisplayName("维度名全都识别不了 → 返回 null，由调用方保留原值（不猜）")
    void returnsNullWhenNoDimensionRecognized() {
        assertThat(ScoreAggregator.computeOverall(List.of(dim("编程能力", 80)))).isNull();
    }

    @Test
    @DisplayName("分数不是数字（字符串/布尔/null）→ 跳过该项，不吞成 0 分")
    void skipsNonNumericScores() {
        List<Map<String, Object>> dims = new ArrayList<>();
        dims.add(dim("岗位匹配度", "80"));
        dims.add(dim("项目/工作经历含金量", 60));
        assertThat(ScoreAggregator.computeOverall(dims)).isEqualTo(60);
    }

    @Test
    @DisplayName("整串 JSON：overallScore 被替换为聚合值")
    void rewritesOverallInJson() {
        String json = "{\"overallScore\":85,\"dimensions\":["
                + "{\"name\":\"岗位匹配度\",\"score\":60,\"suggestion\":\"a\"},"
                + "{\"name\":\"项目/工作经历含金量\",\"score\":50,\"suggestion\":\"b\"}],"
                + "\"strengths\":[\"x\"],\"improvements\":[\"y\"]}";
        // 60*0.3 + 50*0.4 = 38 ；权重和 0.7 → 54.28 → 54
        String out = ScoreAggregator.applyDeterministicOverall(json);
        assertThat(out).contains("\"overallScore\":54");
    }

    @Test
    @DisplayName("JSON 非法 / 无 dimensions → 原样返回，不把结果写坏")
    void returnsInputUntouchedOnBadInput() {
        assertThat(ScoreAggregator.applyDeterministicOverall("not json")).isEqualTo("not json");
        String noDims = "{\"overallScore\":70,\"dimensions\":[]}";
        assertThat(ScoreAggregator.applyDeterministicOverall(noDims)).isEqualTo(noDims);
    }
}
