package com.example.interview.service.job;

import com.example.interview.entity.JobPostingEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 简历 × 岗位 匹配度打分（v1.29.0）
 *
 * 用简历中的技术画像与岗位标题/标签/描述做关键词匹配，输出吻合的技能与综合得分，据此为求职者推荐岗位。
 * 纯规则、确定性、可单测；不调用模型，稳定且零成本。
 */
@Service
public class JobMatchService {

    /** 常见技术画像词表：命中简历即视为候选技能点 */
    private static final String[] SKILL_KEYWORDS = {
            "java", "spring", "springboot", "mybatis", "hibernate", "redis", "mysql", "postgresql",
            "postgres", "mongo", "mongodb", "kafka", "rabbitmq", "rocketmq", "elasticsearch", "es",
            "git", "docker", "kubernetes", "k8s", "linux", "jvm", "netty", "dubbo", "grpc", "websocket",
            "并发", "多线程", "分布式", "微服务", "缓存", "消息队列", "大数据", "算法", "机器学习", "深度学习",
            "大模型", "nlp", "python", "go", "golang", "c++", "c语言", "javascript", "ts", "typescript",
            "react", "vue", "前端", "flutter", "android", "ios", "测试", "运维", "数据分析", "sql", "hive",
            "spark", "flink"
    };

    /**
     * 匹配结果
     *
     * @param missingSkills JD 中明确出现、但简历画像里没有的技能（短板）。按词表顺序输出（确定）。
     *                      <b>规则推导、零 AI</b> —— 与简历优化「未具备只能进 missingKeywords 待补」的
     *                      不编造原则同源：缺什么就说缺什么，不猜、不编。
     */
    public record MatchResult(JobPostingEntity job, int matchScore, List<String> matchedSkills,
                              List<String> missingSkills) {
    }

    /** 提取简历中的技能画像（小写，归一化） */
    public Set<String> extractSkills(String resumeText) {
        Set<String> result = new HashSet<>();
        if (resumeText == null) {
            return result;
        }
        String lower = resumeText.toLowerCase(Locale.ROOT);
        for (String kw : SKILL_KEYWORDS) {
            if (lower.contains(kw.toLowerCase(Locale.ROOT))) {
                result.add(kw.toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }

    /**
     * 对岗位列表按简历匹配度评分并按分数倒序返回。
     * 分数 = 命中技能数 × 20 + 学历精确匹配 +10（本科及以上等取关键词 '本科'）；
     * 未命中任何技能不计分。
     */
    public List<MatchResult> match(String resumeText, List<JobPostingEntity> jobs, int limit) {
        Set<String> skills = extractSkills(resumeText);
        List<MatchResult> result = new ArrayList<>();
        if (jobs == null) {
            return result;
        }
        for (JobPostingEntity job : jobs) {
            String text = concat(job);
            Set<String> jdSkills = extractSkills(text);   // 与命中判定同一份词表、同一段文本（口径同源）
            List<String> hit = new ArrayList<>();
            for (String s : skills) {
                // text 由 concat(job) 生成，永不为 null
                if (text.toLowerCase(Locale.ROOT).contains(s)) {
                    hit.add(s);
                }
            }
            if (hit.isEmpty()) {
                continue; // 完全无关的岗位不推荐
            }
            // 短板：JD 里明确出现、但简历画像里没有的技能（按词表顺序输出，结果确定）。
            // 规则推导、零 AI —— 与简历优化「未具备只能进 missingKeywords 待补」的不编造原则同源。
            List<String> missing = new ArrayList<>();
            for (String kw : SKILL_KEYWORDS) {
                String k = kw.toLowerCase(Locale.ROOT);
                if (jdSkills.contains(k) && !skills.contains(k)) {
                    missing.add(k);
                }
            }
            int score = hit.size() * 20;
            String degree = job.getDegree() == null ? "" : job.getDegree();
            if (degree.contains("本科") && resumeText != null
                    && resumeText.toLowerCase(Locale.ROOT).contains("本科")) {
                score += 10;
            }
            if (degree.contains("硕士") && resumeText != null
                    && resumeText.toLowerCase(Locale.ROOT).contains("硕士")) {
                score += 10;
            }
            result.add(new MatchResult(job, score, hit, missing));
        }
        result.sort((a, b) -> Integer.compare(b.matchScore(), a.matchScore()));
        return limit > 0 ? result.stream().limit(limit).toList() : result;
    }

    private String concat(JobPostingEntity job) {
        StringBuilder sb = new StringBuilder();
        if (job == null) return sb.toString();
        append(sb, job.getTitle());
        append(sb, job.getTags());
        append(sb, job.getDescription());
        append(sb, job.getRequirements());
        append(sb, job.getJobType());
        return sb.toString();
    }

    private void append(StringBuilder sb, String s) {
        if (s != null) {
            sb.append(' ').append(s);
        }
    }
}