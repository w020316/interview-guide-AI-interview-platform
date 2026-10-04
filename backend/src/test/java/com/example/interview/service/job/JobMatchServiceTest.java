package com.example.interview.service.job;

import com.example.interview.entity.JobPostingEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 简历 × 岗位 匹配度打分测试
 */
@DisplayName("简历岗位匹配打分测试")
class JobMatchServiceTest {

    private final JobMatchService matcher = new JobMatchService();

    private JobPostingEntity job(String title, String tags, String desc) {
        return JobPostingEntity.builder()
                .title(title).tags(tags).description(desc).degree("本科及以上").build();
    }

    @Test
    @DisplayName("按命中的技能打分并倒序返回")
    void match_ranksBySkillHit() {
        List<JobPostingEntity> jobs = List.of(
                job("Java 后端工程师", "Java,Spring,Redis", "写核心系统"),
                job("算法工程师", "算法,机器学习,Python", "做模型"),
                job("前端工程师", "Vue,React", "写页面")
        );
        // 简历画像：Java + 算法命中前两岗，前端岗不命中
        var result = matcher.match("熟悉 Java 与算法，做过机器学习", jobs, 10);

        assertThat(result).hasSize(2);
        // 分数倒序：算法岗命中 算法+机器学习 应排最前
        assertThat(result.get(0).job().getTitle()).isEqualTo("算法工程师");
        assertThat(result.get(0).matchedSkills()).contains("算法");
        // 全体中至少有一岗命中 Java
        assertThat(result).anyMatch(r -> r.matchedSkills().contains("java"));
    }

    @Test
    @DisplayName("无技能命中时返回空")
    void match_noSkillHit_empty() {
        List<JobPostingEntity> jobs = List.of(job("市场运营", "市场", "负责推广"));
        assertThat(matcher.match("我学习会计", jobs, 10)).isEmpty();
    }

    @Test
    @DisplayName("extractSkills 提取简历中小写技能画像")
    void extractSkills_normalizesCase() {
        var skills = matcher.extractSkills("我用 Java、Redis 和 Kubernetes 部署");
        assertThat(skills).contains("java", "redis", "kubernetes");
    }

    @Test
    @DisplayName("短板：JD 明确出现但简历没有的技能，按词表顺序输出（零 AI 规则推导）")
    void match_missingSkills_rules() {
        var jobs = List.of(
                job("Java 后端工程师", "Java,Spring,Redis,Kafka", "用 Kafka 与 Redis 做高并发"));
        var result = matcher.match("熟悉 Java、Spring，做过核心系统", jobs, 10);

        assertThat(result).hasSize(1);
        var r = result.get(0);
        assertThat(r.matchedSkills()).contains("java", "spring");
        // JD 的 tags 与 description 都提到 redis/kafka/「高并发」，简历没有 → 短板（按词表顺序，中英混合）
        assertThat(r.missingSkills()).containsExactly("redis", "kafka", "并发");
        // JD 没提的技能不得出现在短板里（不编造）
        assertThat(r.missingSkills()).doesNotContain("mongodb", "vue", "react");
    }

    @Test
    @DisplayName("短板：简历已覆盖 JD 全部技能时为空（不输出无意义空档）")
    void match_missingSkills_emptyWhenCovered() {
        var jobs = List.of(job("Java 后端工程师", "Java,Spring", "纯 Java 栈"));
        var result = matcher.match("Java Spring SpringBoot Redis", jobs, 10);

        assertThat(result.get(0).missingSkills()).isEmpty();
    }

    @Test
    @DisplayName("短板：JD 的别名技能会被简历的更具体技能覆盖（postgres ⊂ PostgreSQL）")
    void match_missingSkills_aliasCoveredByResume() {
        // JD 明确写了 postgres / es / sql，但简历写的是更具体的 PostgreSQL —— 不应误报为短板
        var jobs = List.of(job("数据工程师", "PostgreSQL,Elasticsearch,ES", "postgres elasticsearch es sql"));
        var result = matcher.match("熟悉 PostgreSQL 与 Elasticsearch", jobs, 10);

        assertThat(result.get(0).missingSkills()).isEmpty();
    }
}