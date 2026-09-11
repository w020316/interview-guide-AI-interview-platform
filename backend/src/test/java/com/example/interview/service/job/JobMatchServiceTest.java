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
}